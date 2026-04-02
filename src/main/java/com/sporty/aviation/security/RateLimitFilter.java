package com.sporty.aviation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.aviation.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Filter 2 of 2 — Per-API-Key Rate Limiting.
 *
 * <p>Runs after {@link ApiKeyAuthFilter} ({@code @Order(1)}), so by the time
 * this filter executes the API key is already validated — no need to re-check it.
 *
 * <p><b>Algorithm — Fixed Window Counter backed by Redis:</b>
 * <ol>
 *   <li>Redis key: {@code rate_limit:{api-key}} (e.g. {@code rate_limit:key-abc123})</li>
 *   <li>On each request: atomically {@code INCR} the counter.</li>
 *   <li>On the first request in a window (counter == 1): set a 60-second TTL.</li>
 *   <li>If the counter exceeds the limit: return {@code 429 Too Many Requests}
 *       with a {@code Retry-After} header showing seconds until the window resets.</li>
 * </ol>
 *
 * <p><b>Why Redis for rate limiting?</b>
 * The counter lives in Redis, not in JVM memory. If the service scales to multiple
 * instances, all instances share the same counter — the rate limit is enforced
 * correctly across the whole cluster.
 *
 * <p><b>Rate limit headers on every response (including successful ones):</b>
 * <ul>
 *   <li>{@code X-RateLimit-Limit} — maximum requests allowed per minute</li>
 *   <li>{@code X-RateLimit-Remaining} — requests left in the current window</li>
 *   <li>{@code Retry-After} — seconds until the window resets (only on 429)</li>
 * </ul>
 *
 * <p>Configuration:
 * <pre>
 *   security.rate-limit.requests-per-minute=60
 * </pre>
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMIT_PREFIX = "rate_limit:";
    private static final String ACTUATOR_PATH     = "/actuator";
    private static final String SWAGGER_UI        = "/swagger-ui";
    private static final String API_DOCS          = "/v3/api-docs";
    private static final long   WINDOW_SECONDS    = 60L;

    /**
     * Lua script that atomically increments the request counter and sets its
     * expiry in a single Redis round-trip.
     *
     * <p>Running both commands inside Lua guarantees atomicity on the Redis server:
     * both succeed or neither does. This prevents the counter from existing without
     * a TTL, which would permanently block an API key.
     *
     * <p>KEYS[1] = the rate-limit Redis key (e.g. "rate_limit:sporty-key-abc")
     * ARGV[1]  = the window duration in seconds (e.g. "60")
     */
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    @Value("${security.rate-limit.requests-per-minute}")
    private int requestsPerMinute;

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        // Public paths bypass rate limiting (same set as ApiKeyAuthFilter).
        String uri = request.getRequestURI();
        if (uri.startsWith(ACTUATOR_PATH)
                || uri.startsWith(SWAGGER_UI)
                || uri.startsWith(API_DOCS)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey  = request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER);
        String redisKey = RATE_LIMIT_PREFIX + apiKey;

        // Atomically INCR the counter AND set the TTL in one Lua script.
        // If Redis is unavailable, execute() returns null → currentCount = 0 → request passes
        // through (fail-open: prefer availability over strict rate limiting on Redis outage).
        Long count = redisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of(redisKey),
                String.valueOf(WINDOW_SECONDS)
        );

        long currentCount = count != null ? count : 0;
        long remaining    = Math.max(0, requestsPerMinute - currentCount);

        // Always add these headers so clients can track their own usage proactively.
        response.addHeader("X-RateLimit-Limit",     String.valueOf(requestsPerMinute));
        response.addHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        if (currentCount > requestsPerMinute) {
            Long ttl        = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            long retryAfter = (ttl != null && ttl > 0) ? ttl : WINDOW_SECONDS;

            response.addHeader("Retry-After", String.valueOf(retryAfter));

            // Log only the last 4 chars of the key to avoid leaking it in logs.
            String maskedKey = apiKey.length() > 4
                    ? "****" + apiKey.substring(apiKey.length() - 4)
                    : "****";
            log.warn("Rate limit exceeded for key {} on '{}' from {} — retry after {}s",
                    maskedKey, request.getRequestURI(), getClientIp(request), retryAfter);

            writeError(response, request.getRequestURI(),
                    429,
                    "Too Many Requests",
                    "Rate limit exceeded (" + requestsPerMinute + " req/min). "
                            + "Retry after " + retryAfter + " seconds.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Returns the real client IP address.
     *
     * <p>When the app runs behind Nginx, {@code request.getRemoteAddr()} returns
     * Nginx's internal container IP (e.g. 172.18.0.3), not the actual caller.
     * Nginx sets the {@code X-Real-IP} header to the original client IP, so we
     * read that first and fall back to {@code getRemoteAddr()} for direct calls.
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        return (ip != null && !ip.isBlank()) ? ip : request.getRemoteAddr();
    }

    private void writeError(HttpServletResponse response, String path,
                            int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                objectMapper.writeValueAsString(
                        ErrorResponse.builder()
                                .status(status)
                                .error(error)
                                .message(message)
                                .path(path)
                                .timestamp(LocalDateTime.now())
                                .build()
                )
        );
    }
}
