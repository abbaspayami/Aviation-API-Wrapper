package com.sporty.aviation.client;

import com.sporty.aviation.exception.AviationApiException;
import com.sporty.aviation.exception.AviationClientException;
import feign.Logger;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Feign configuration scoped exclusively to {@link AirportDbFeignClient} (Provider 2).
 *
 * <p>Intentionally NOT annotated with {@code @Configuration}.
 * When Feign sees {@code configuration = AirportDbFeignClientConfig.class},
 * it creates a child ApplicationContext for AirportDB only — so the beans
 * defined here do NOT conflict with {@link FeignClientConfig} beans used
 * by the primary Aviation Weather client.
 *
 * <p>Key differences from the primary client config:
 * <ul>
 *   <li>Shorter timeouts (2 s connect / 5 s read) — configured in {@code application.yml}
 *       under {@code spring.cloud.openfeign.client.config.airport-db-api}.
 *       We're already in the fallback path, so we fail fast.</li>
 *   <li>API token injected automatically via a {@link RequestInterceptor}
 *       so the Feign interface stays clean (no token param on every method).</li>
 * </ul>
 */
public class AirportDbFeignClientConfig {

    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }

    /**
     * Appends the AirportDB API token as a query parameter to every request.
     *
     * <p>AirportDB authenticates via {@code ?apiToken={token}} on each call.
     * Using a {@link RequestInterceptor} means the token is added in one place —
     * no need to pass it through every method signature.
     */
    @Bean
    public RequestInterceptor apiTokenInterceptor(
            @Value("${aviation.provider2.api-token}") String apiToken) {
        return template -> template.query("apiToken", apiToken);
    }

    /**
     * Converts AirportDB non-2xx responses into typed exceptions.
     *
     * <p>4xx responses (e.g. 404 airport not found, 401 bad token) throw
     * {@link AviationClientException} — these are permanent errors and must
     * NOT be retried. 5xx responses throw {@link AviationApiException} and
     * are eligible for retry under the "provider2Api" Resilience4j instance.
     */
    @Bean
    public ErrorDecoder provider2ErrorDecoder() {
        return (methodKey, response) -> {
            int status = response.status();
            String msg = "Provider 2 (AirportDB) returned HTTP " + status + ". ";
            return (status >= 400 && status < 500)
                    ? new AviationClientException(msg + "Not retrying — client-side error.", status)
                    : new AviationApiException(msg + "Upstream may be degraded.");
        };
    }
}
