package com.sporty.aviation.exception;

import lombok.Getter;

/**
 * Thrown when an upstream API returns a 4xx HTTP response.
 *
 * <ul>
 *   <li>{@link AviationApiException} — 5xx server error: transient, worth retrying.</li>
 *   <li>{@code AviationClientException} — 4xx client error: permanent, retrying
 *       will never produce a different result.</li>
 * </ul>
 *
 * <p>Keeping them as sibling types (both rooted at {@link RuntimeException}) makes
 * the retry boundary <em>structurally enforced by the type system</em>:
 * {@code retry-exceptions: [AviationApiException]} can never accidentally match a
 * 4xx response because {@code AviationClientException} is not a subtype of
 * {@code AviationApiException}. No {@code ignore-exceptions} entry is needed to
 * guard against it — the hierarchy prevents it outright.
 *
 * <p>Examples:
 * <ul>
 *   <li>400 Bad Request — invalid query parameter sent to the upstream API</li>
 *   <li>404 Not Found   — the API endpoint URL has changed</li>
 * </ul>
 */
@Getter
public class AviationClientException extends RuntimeException {

    private final int httpStatus;

    public AviationClientException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }
}
