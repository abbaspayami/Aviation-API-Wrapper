package com.sporty.aviation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporty.aviation.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Filter 1 of 2 — API Key Authentication.
 *
 * <p>Runs before every request (except {@code /actuator/**}).
 * Reads the {@code X-API-Key} header and rejects the request with
 * {@code 401 Unauthorized} if the key is missing or not in the configured set.
 *
 * <p>Execution order: this filter runs at {@code @Order(1)}, before
 * {@link RateLimitFilter} ({@code @Order(2)}). This ensures we never
 * count an invalid key against the rate limit.
 *
 * <p>Configuration: add valid keys in {@code application.properties}:
 * <pre>
 *   security.api-keys=key-abc123,key-xyz789
 * </pre>
 * Or via environment variable (recommended for production):
 * <pre>
 *   SECURITY_API_KEYS=key-abc123,key-xyz789
 * </pre>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Header name callers must include on every request. */
    public static final String API_KEY_HEADER = "X-API-Key";

    private static final String ACTUATOR_PATH  = "/actuator";
    private static final String SWAGGER_UI     = "/swagger-ui";
    private static final String API_DOCS       = "/v3/api-docs";

    private final ApiKeyProperties apiKeyProperties;
    private final ObjectMapper     objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        // Public paths — excluded from API key requirement:
        //   /actuator/**  — health checks and metrics for ops tooling
        //   /swagger-ui/** — Swagger UI (browser)
        //   /v3/api-docs/** — OpenAPI spec (JSON/YAML)
        String uri = request.getRequestURI();
        if (uri.startsWith(ACTUATOR_PATH)
                || uri.startsWith(SWAGGER_UI)
                || uri.startsWith(API_DOCS)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (!StringUtils.hasText(apiKey) || !apiKeyProperties.getApiKeys().contains(apiKey)) {
            log.warn("Rejected request to '{}' from {} — missing or invalid API key",
                    request.getRequestURI(), getClientIp(request));
            writeError(response, request.getRequestURI(),
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized",
                    "Missing or invalid API key. Include a valid 'X-API-Key' header.");
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
