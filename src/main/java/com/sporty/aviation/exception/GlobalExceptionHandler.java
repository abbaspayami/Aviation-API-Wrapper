package com.sporty.aviation.exception;

import com.sporty.aviation.dto.ErrorResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AirportNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAirportNotFound(
            AirportNotFoundException ex, HttpServletRequest request) {

        log.warn("Airport not found — ICAO: '{}' path: {}", ex.getMessage(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse.builder()
                        .status(HttpStatus.NOT_FOUND.value())
                        .error("Not Found")
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now())
                        .build()
        );
    }

    /**
     * Handles 4xx responses received from an upstream provider.
     *
     * <p>{@link AviationClientException} means <em>we</em> sent an invalid request
     * to the upstream — wrong base URL, expired token, malformed parameter, etc.
     * This is always a misconfiguration on our side; the upstream correctly rejected us.
     * The end user is NOT informed of the upstream detail — they receive a clean
     * {@code 502 Bad Gateway} because, as far as they are concerned, we (the gateway)
     * failed to obtain a valid response from the data provider.
     *
     * <p>Why {@code 502} and not {@code 503}:
     * {@code 502} means "I am a gateway and I could not get a valid response from
     * upstream." {@code 503} means "I myself am overloaded or down." The problem
     * here is the upstream integration, not our server — so {@code 502} is accurate.
     *
     * <p>Why kept separate from {@link #handleAviationApiError}:
     * Both return {@code 502}, but this handler logs at {@code ERROR} — a 4xx from
     * upstream is a misconfiguration that will break <em>every</em> request until
     * a developer fixes it.  {@link AviationApiException} (5xx) logs at {@code WARN}
     * because it is transient and may self-heal.  The distinction matters for
     * on-call alerting rules.
     *
     * <p>This exception is NOT retried and does NOT count toward the circuit-breaker
     * failure rate (both enforced in {@code application.yml} via the type hierarchy).
     */
    @ExceptionHandler(AviationClientException.class)
    public ResponseEntity<ErrorResponse> handleAviationClientError(
            AviationClientException ex, HttpServletRequest request) {

        // Log at ERROR — needs immediate dev attention; every request will fail
        // until the underlying misconfiguration is corrected.
        log.error("Upstream integration error (HTTP {}) — the provider rejected our request. " +
                  "This is a misconfiguration, not a transient failure. " +
                  "path: {} detail: {}",
                  ex.getHttpStatus(), request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ErrorResponse.builder()
                        .status(HttpStatus.BAD_GATEWAY.value())
                        .error("Bad Gateway")
                        .message("Unable to retrieve aviation data at this time. Please try again later.")
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now())
                        .build()
        );
    }

    /**
     * Handles 5xx / transient errors received from an upstream provider.
     *
     * <p>{@link AviationApiException} is thrown when the upstream server itself
     * is degraded — it returned a 5xx status, timed out, or was unreachable.
     * This is a temporary condition; the Retry aspect already attempted up to
     * 3 calls with exponential back-off before this handler is reached.
     *
     * <p>Logged at {@code WARN} (not {@code ERROR}) because the problem is in the
     * upstream provider, not in our code.  If it persists, the circuit breaker
     * will open automatically and surface as a {@code 503} via
     * {@link #handleCircuitBreakerOpen}.
     */
    @ExceptionHandler(AviationApiException.class)
    public ResponseEntity<ErrorResponse> handleAviationApiError(
            AviationApiException ex, HttpServletRequest request) {

        log.warn("Upstream server error (transient) — path: {} detail: {}",
                 request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ErrorResponse.builder()
                        .status(HttpStatus.BAD_GATEWAY.value())
                        .error("Bad Gateway")
                        .message("Unable to retrieve aviation data at this time. Please try again later.")
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now())
                        .build()
        );
    }

    /**
     * The circuit breaker is OPEN — too many recent upstream failures.
     * Return 503 so the caller knows to back off and retry later.
     * The circuit will automatically recover after 30 s (half-open probe).
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitBreakerOpen(
            CallNotPermittedException ex, HttpServletRequest request) {

        log.warn("Circuit breaker OPEN — rejecting request to: {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ErrorResponse.builder()
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .error("Service Unavailable")
                        .message("Aviation API is temporarily unavailable. Please try again in a few moments.")
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now())
                        .build()
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleValidationError(
            ConstraintViolationException ex, HttpServletRequest request) {

        log.warn("Validation failed — path: {} violations: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ErrorResponse.builder()
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Bad Request")
                        .message(ex.getMessage())
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now())
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericError(
            Exception ex, HttpServletRequest request) {

        log.error("Unhandled exception — path: {}", request.getRequestURI(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ErrorResponse.builder()
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .error("Internal Server Error")
                        .message("An unexpected error occurred")
                        .path(request.getRequestURI())
                        .timestamp(LocalDateTime.now())
                        .build()
        );
    }
}
