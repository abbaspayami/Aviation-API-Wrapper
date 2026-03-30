package com.sporty.aviation.exception;

import lombok.Getter;

/**
 * Thrown when an upstream API returns a 4xx HTTP response.
 *
 * <p>Extends {@link AviationApiException} so the {@code GlobalExceptionHandler}
 * and circuit breaker can still catch it via the parent type when needed.
 *
 * <p>Unlike {@link AviationApiException} (which covers transient 5xx failures),
 * this exception represents a <em>permanent</em> client-side error — the request
 * is malformed, the endpoint is wrong, or the resource does not exist.
 * Retrying will not change the outcome, so it is listed in {@code ignore-exceptions}
 * for every Resilience4j Retry instance.
 *
 * <p>Examples:
 * <ul>
 *   <li>400 Bad Request — invalid query parameter sent to the upstream API</li>
 *   <li>404 Not Found   — the API endpoint URL has changed</li>
 *   <li>429 Too Many Requests — rate limit hit; backing off is handled separately</li>
 * </ul>
 */
@Getter
public class AviationClientException extends AviationApiException {

    private final int httpStatus;

    public AviationClientException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }
}
