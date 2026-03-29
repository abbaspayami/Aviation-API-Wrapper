package com.sporty.aviation.client;

import com.sporty.aviation.exception.AviationApiException;
import feign.Logger;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign client configuration shared by all {@code @FeignClient} beans in this package.
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
 */
@Configuration
public class FeignClientConfig {

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

        @Override
        public Exception decode(String methodKey, Response response) {
            int status = response.status();

            return switch (status) {
                case 400 -> new AviationApiException(
                        "Bad request sent to Aviation Weather API (HTTP 400). " +
                        "Check the ICAO code format.");
                case 404 -> new AviationApiException(
                        "Aviation Weather API endpoint not found (HTTP 404). " +
                        "The API URL may have changed.");
                case 429 -> new AviationApiException(
                        "Aviation Weather API rate limit exceeded (HTTP 429). " +
                        "Please slow down requests.");
                case 500, 502, 503 -> new AviationApiException(
                        "Aviation Weather API is temporarily unavailable (HTTP " + status + "). " +
                        "Please try again later.");
                default  -> new AviationApiException(
                        "Unexpected response from Aviation Weather API (HTTP " + status + ").");
            };
        }
    }
}
