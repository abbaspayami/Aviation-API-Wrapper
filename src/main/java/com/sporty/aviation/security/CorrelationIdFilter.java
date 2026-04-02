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
 * <h3>ID generation strategy</h3>
 * <p>A fresh UUID is <strong>always</strong> generated internally — never taken
 * directly from the caller. This guarantees:
 * <ul>
 *   <li>Consistent UUID format in every log line.</li>
 *   <li>No log-injection risk from malicious or malformed header values.</li>
 *   <li>Uniqueness — callers cannot collide with or spoof an existing ID.</li>
 * </ul>
 *
 * <h3>External trace ID (distributed tracing)</h3>
 * <p>If the caller supplies an {@code X-Correlation-ID} header (e.g. from an
 * upstream gateway or another service), it is stored separately in MDC under
 * {@code "externalTraceId"} — never used as our own internal ID. This lets
 * operators cross-reference the caller's trace chain without trusting the
 * caller's value as our own identifier.
 *
 * <h3>Thread safety</h3>
 * <p>MDC entries are cleared in a {@code finally} block — critical for
 * Tomcat thread-pool reuse. Without cleanup, a reused thread would carry
 * the previous request's ID into the next request.
 *
 * <p>The MDC key {@code "correlationId"} is referenced in the log pattern:
 * <pre>
 *   logging.pattern.console: "... [%X{correlationId:-no-id}] ..."
 * </pre>
 */
@Component
@Order(0)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Header name echoed back in responses so callers can match their logs to ours. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /** MDC key for our internally generated ID — appears in every log line. */
    private static final String MDC_KEY              = "correlationId";

    /**
     * MDC key for the caller-supplied ID — stored separately for cross-system
     * tracing, never used as our own internal correlation ID.
     */
    private static final String MDC_EXTERNAL_KEY     = "externalTraceId";

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        // Always generate a fresh UUID — guaranteed format, guaranteed uniqueness,
        // no dependency on caller input. This is our internal correlation ID.
        String internalId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, internalId);

        // If the caller provided their own trace ID (e.g. from an upstream gateway),
        // store it separately. Never used as our ID — only for cross-system correlation.
        String externalId = request.getHeader(CORRELATION_ID_HEADER);
        if (StringUtils.hasText(externalId)) {
            MDC.put(MDC_EXTERNAL_KEY, externalId);
        }

        // Echo OUR generated ID back so callers can match their request to our logs.
        response.setHeader(CORRELATION_ID_HEADER, internalId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always remove both entries — prevents IDs from leaking into the
            // next request handled by the same Tomcat worker thread.
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_EXTERNAL_KEY);
        }
    }
}
