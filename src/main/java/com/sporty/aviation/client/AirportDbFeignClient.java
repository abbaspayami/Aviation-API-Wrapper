package com.sporty.aviation.client;

import com.sporty.aviation.dto.Provider2AirportDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative Feign HTTP client for Provider 2 — AirportDB.io.
 *
 * <p>Used exclusively as a fallback when the primary Aviation Weather API
 * (aviationweather.gov) is unreachable, timing out, or returning 5xx errors.
 *
 * <p>API details:
 * <ul>
 *   <li>Base URL: configured via {@code aviation.provider2.base-url} in application.properties</li>
 *   <li>Auth: API token appended automatically by {@link AirportDbFeignClientConfig#apiTokenInterceptor}</li>
 *   <li>Docs: <a href="https://airportdb.io">airportdb.io</a></li>
 * </ul>
 *
 * <p>Unlike the primary client, AirportDB returns a single JSON <b>object</b>
 * (not a list) for a single ICAO lookup.
 */
@FeignClient(
        name          = "airport-db-api",
        url           = "${aviation.provider2.base-url}",
        configuration = AirportDbFeignClientConfig.class
)
public interface AirportDbFeignClient {

    /**
     * Fetches airport data by ICAO code.
     *
     * <p>Equivalent HTTP call (token added automatically by the interceptor):
     * <pre>
     *   GET https://airportdb.io/api/v1/airport/EGLL?apiToken={token}
     * </pre>
     *
     * @param icao the 4-letter ICAO airport identifier (e.g. "EGLL")
     * @return the airport details from AirportDB
     */
    @GetMapping("/airport/{icao}")
    Provider2AirportDto getAirportByIcao(@PathVariable("icao") String icao);
}
