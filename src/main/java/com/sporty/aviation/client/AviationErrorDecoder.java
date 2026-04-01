package com.sporty.aviation.client;

import com.sporty.aviation.exception.AviationApiException;
import com.sporty.aviation.exception.AviationClientException;
import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * Shared Feign {@link ErrorDecoder} used by every Aviation API Feign client.
 *
 * <p>Translates non-2xx HTTP responses from an upstream provider into our own
 * typed exceptions, so callers never have to deal with raw {@code FeignException}s.
 *
 * <h3>Retry-safety contract</h3>
 * <ul>
 *   <li><b>4xx → {@link AviationClientException}</b> — permanent errors; these
 *       represent a misconfiguration or a bad request on our side.  The Resilience4j
 *       Retry aspect is configured with
 *       {@code retry-exceptions: [AviationApiException]} and
 *       {@code ignore-exceptions: [AviationClientException]}, so 4xx responses are
 *       <em>structurally excluded</em> from retry by the exception hierarchy
 *       (they are sibling types, not subtypes of {@link AviationApiException}).</li>
 *   <li><b>5xx → {@link AviationApiException}</b> — transient server-side failures.
 *       The Retry aspect will attempt up to 3 calls with exponential back-off before
 *       propagating the exception to the caller.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * Each Feign client config creates its own instance via
 * {@code new AviationErrorDecoder("Provider Name")}.  The {@code providerName}
 * is embedded in every exception message so log lines are unambiguous when
 * multiple providers are active.
 *
 * @see AviationWeatherFeignClientConfig
 * @see AirportDbFeignClientConfig
 */
public class AviationErrorDecoder implements ErrorDecoder {

    private final String providerName;

    /**
     * @param providerName human-readable label for the upstream provider
     *                     (e.g. {@code "Aviation Weather API"}, {@code "AirportDB"}).
     *                     Embedded in every exception message for clear log attribution.
     */
    public AviationErrorDecoder(String providerName) {
        this.providerName = providerName;
    }

    /**
     * Maps an HTTP error status to a typed exception.
     *
     * <p>Explicit cases for the most common status codes give callers and
     * operators precise, actionable messages.  The {@code default} arms handle
     * any unexpected status code by bucketing it into the correct family
     * (4xx → client error, everything else → server error).
     */
    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();

        return switch (status) {

            // ── 4xx — permanent client-side errors ──────────────────────────────
            // These indicate a bug or misconfiguration on our side.  Retrying
            // will produce the exact same result, so they must never be retried.

            case 400 -> new AviationClientException(
                    providerName + " rejected the request (HTTP 400 Bad Request). " +
                    "Check the ICAO code format or request parameters.", status);

            case 401 -> new AviationClientException(
                    providerName + " rejected the request (HTTP 401 Unauthorized). " +
                    "Check that the API token is valid and correctly configured.", status);

            case 403 -> new AviationClientException(
                    providerName + " denied access (HTTP 403 Forbidden). " +
                    "The API token may have expired or lack sufficient permissions.", status);

            case 404 -> new AviationClientException(
                    providerName + " endpoint not found (HTTP 404 Not Found). " +
                    "The upstream API URL may have changed — verify the base URL configuration.", status);

            // ── 5xx — transient server-side errors ──────────────────────────────
            // The upstream is degraded.  Retry with exponential back-off is
            // worthwhile; the circuit breaker will open if failures persist.

            case 500 -> new AviationApiException(
                    providerName + " encountered an internal error (HTTP 500). " +
                    "Please try again later.");

            case 502 -> new AviationApiException(
                    providerName + " received a bad response from its own upstream (HTTP 502). " +
                    "Please try again later.");

            case 503 -> new AviationApiException(
                    providerName + " is currently unavailable (HTTP 503 Service Unavailable). " +
                    "Please try again later.");

            case 504 -> new AviationApiException(
                    providerName + " did not respond in time (HTTP 504 Gateway Timeout). " +
                    "Please try again later.");

            // ── Catch-all: bucket by family ─────────────────────────────────────
            default -> (status >= 400 && status < 500)
                    ? new AviationClientException(
                            providerName + " returned an unexpected client error (HTTP " + status + "). " +
                            "This is likely a misconfiguration — the request will not be retried.", status)
                    : new AviationApiException(
                            providerName + " returned an unexpected server error (HTTP " + status + "). " +
                            "Please try again later.");
        };
    }
}
