package com.sporty.aviation.client;

import feign.Logger;
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
 *       {@code FeignException}s that leak HTTP details to callers.
 *       Delegates to the shared {@link AviationErrorDecoder}.</li>
 * </ul>
 *
 * <p>Timeouts (connect: 3s, read: 10s) are configured in {@code application.yml}
 * under {@code spring.cloud.openfeign.client.config.default} — no hardcoded
 * {@code Request.Options} bean is needed here.
 *
 * @see AirportDbFeignClientConfig for the equivalent config used by the fallback client
 * @see AviationErrorDecoder for the shared error-decoding logic
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
     *
     * <p>Uses the shared {@link AviationErrorDecoder} with the provider name
     * "Aviation Weather API" so log messages are clearly attributed to this source.
     */
    @Bean
    public ErrorDecoder aviationErrorDecoder() {
        return new AviationErrorDecoder("Aviation Weather API");
    }
}
