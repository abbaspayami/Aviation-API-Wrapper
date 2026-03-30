package com.sporty.aviation.client;

import com.sporty.aviation.exception.AviationApiException;
import com.sporty.aviation.exception.AviationClientException;
import feign.Logger;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign client configuration scoped exclusively to {@link AviationWeatherFeignClient}.
 *
 * <p>Registers two beans:
 * <ul>
 *   <li>{@link Logger.Level} — controls how much HTTP detail Feign logs.</li>
 *   <li>{@link ErrorDecoder} — translates non-2xx HTTP responses from the
 *       Aviation Weather API into our own typed exceptions instead of raw
 *       {@code FeignException}s that leak HTTP details to callers.</li>
 * </ul>
 *
 * <p>Timeouts (connect: 3s, read: 10s) are configured in {@code application.yml}
 * under {@code spring.cloud.openfeign.client.config.default} — no hardcoded
 * {@code Request.Options} bean is needed here.
 *
 * @see AirportDbFeignClientConfig for the equivalent config used by the fallback client
 */
@Configuration
public class AviationWeatherFeignClientConfig {

    /**
     * Sets the Feign log level to BASIC so request/response summaries appear
     * in the application log without printing full headers or bodies.
     *
     * <p>Log levels:
     * <ul>
     *   <li>NONE    — no logging (production default)</li>
     *   <li>BASIC   — method, URL, status code, execution time</li>
     *   <li>HEADERS — BASIC + request/response headers</li>
     *   <li>FULL    — HEADERS + body (useful while debugging)</li>
     * </ul>
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }

    /**
     * Translates HTTP error responses from the Aviation Weather API into
     * application-specific exceptions so callers never have to deal with
     * raw {@code FeignException} types.
     */
    @Bean
    public ErrorDecoder aviationErrorDecoder() {
        return new AviationErrorDecoder();
    }

    // -------------------------------------------------------------------------
    // Inner class: AviationErrorDecoder
    // -------------------------------------------------------------------------

    static class AviationErrorDecoder implements ErrorDecoder {

        /**
         * Maps HTTP status codes to typed exceptions.
         *
         * <p><b>4xx → {@link AviationClientException}</b> (not retried)<br>
         * These are permanent errors: a bad request, a wrong URL, or a rate-limit
         * hit. Retrying will not change the result, so the Retry aspect ignores
         * this exception type via {@code ignore-exceptions} in application.yml.
         *
         * <p><b>5xx → {@link AviationApiException}</b> (retried + counted by CB)<br>
         * These are transient server-side failures. The Retry aspect will attempt
         * up to 3 calls with exponential back-off before giving up.
         */
        @Override
        public Exception decode(String methodKey, Response response) {
            int status = response.status();

            return switch (status) {
                // 4xx — permanent client errors, do NOT retry
                case 400 -> new AviationClientException(
                        "Bad request sent to Aviation Weather API (HTTP 400). " +
                        "Check the ICAO code format.", status);
                case 404 -> new AviationClientException(
                        "Aviation Weather API endpoint not found (HTTP 404). " +
                        "The API URL may have changed.", status);
                case 429 -> new AviationClientException(
                        "Aviation Weather API rate limit exceeded (HTTP 429). " +
                        "Please slow down requests.", status);
                // 5xx — transient server errors, retry is worthwhile
                case 500, 502, 503 -> new AviationApiException(
                        "Aviation Weather API is temporarily unavailable (HTTP " + status + "). " +
                        "Please try again later.");
                default -> status >= 400 && status < 500
                        ? new AviationClientException(
                                "Unexpected client error from Aviation Weather API (HTTP " + status + ").", status)
                        : new AviationApiException(
                                "Unexpected server error from Aviation Weather API (HTTP " + status + ").");
            };
        }
    }
}
