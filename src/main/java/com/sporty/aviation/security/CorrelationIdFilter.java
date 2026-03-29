package com.sporty.aviation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter 0 of 3 — Correlation ID injection.
 *
 * <p>Runs at {@code @Order(0)} — before {@link ApiKeyAuthFilter} (Order 1)
 * and {@link RateLimitFilter} (Order 2). This ensures every log line
 * produced by any subsequent filter, service, or handler includes the
 * correlation ID automatically via SLF4J MDC.
 *
 * <p><b>What it does:</b>
 * <ol>
 *   <li>Reads the {@code X-Correlation-ID} request header if provided by
 *       the caller (useful for end-to-end distributed tracing).</li>
 *   <li>Generates a UUID if no header was supplied.</li>
 *   <li>Stores the ID in {@link MDC} under the key {@code "correlationId"}.</li>
 *   <li>Echoes the ID back in the response header so callers can match
 *       their own logs to server-side log lines.</li>
 *   <li>Clears the MDC entry in a {@code finally} block — critical for
 *       Tomcat thread-pool reuse. Without cleanup, a reused thread would
 *       carry the previous request's ID into the next request.</li>
 * </ol>
 *
 * <p>The MDC key {@code "correlationId"} is referenced in the log pattern:
 * <pre>
 *   logging.pattern.console: "... [%X{correlationId:-no-id}] ..."
 * </pre>
 */
@Component
@Order(0)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Header name read from incoming requests and echoed in responses. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /** MDC key used in the log pattern: {@code %X{correlationId}}. */
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        // Use the caller-supplied ID if present (supports distributed tracing),
        // otherwise generate a new UUID for this request.
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);

        // Echo back so the caller can match their logs to ours.
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always remove — prevents the ID from leaking into the next
            // request handled by the same Tomcat worker thread.
            MDC.remove(MDC_KEY);
        }
    }
}
