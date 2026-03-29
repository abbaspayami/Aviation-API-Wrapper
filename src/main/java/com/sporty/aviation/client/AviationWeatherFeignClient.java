package com.sporty.aviation.client;

import com.sporty.aviation.dto.AviationWeatherAirportDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Declarative Feign HTTP client for the public Aviation Weather API (NOAA/AWC).
 *
 * <p>How OpenFeign works:
 * <ol>
 *   <li>You define this interface with {@code @FeignClient} and annotate methods
 *       with standard Spring MVC annotations ({@code @GetMapping}, {@code @RequestParam}…).</li>
 *   <li>At startup, Spring generates a concrete implementation that handles all
 *       HTTP mechanics (building the URL, serialising/deserialising JSON, error handling).</li>
 *   <li>You inject this interface anywhere as a normal Spring bean — no manual
 *       URL building, no response parsing.</li>
 * </ol>
 *
 * <p>API details:
 * <ul>
 *   <li>Base URL: configured via {@code aviation.api.base-url} in application.properties</li>
 *   <li>No API key required — fully public endpoint provided by NOAA</li>
 *   <li>Docs: <a href="https://aviationweather.gov/data/api/">aviationweather.gov/data/api</a></li>
 * </ul>
 *
 * <p>Why the API always returns a List:
 * The {@code ids} query parameter accepts one or more comma-separated ICAO codes
 * (e.g., {@code ?ids=KJFK,EGLL,OMDB}). Because of this, the API always returns a
 * JSON <b>array</b> — even when you query a single code. Our service will always
 * receive a list of 0 (not found) or 1 (found) elements for a single ICAO lookup.
 */
@FeignClient(
        name            = "aviation-weather-api",
        url             = "${aviation.api.base-url}",
        configuration   = FeignClientConfig.class
)
public interface AviationWeatherFeignClient {

    /**
     * Fetches airport data by ICAO code.
     *
     * <p>Equivalent HTTP call:
     * <pre>
     *   GET https://aviationweather.gov/api/data/airport?ids=KJFK&format=json
     * </pre>
     *
     * @param icaoCode the 4-letter ICAO airport identifier (e.g., "KJFK")
     * @return a list of matching airports — always 0 or 1 items for a single ICAO code
     */
    @GetMapping("/airport")
    List<AviationWeatherAirportDto> getAirportByIcao(
            @RequestParam("ids")    String icaoCode,
            @RequestParam("format") String format
    );

    /**
     * Convenience overload that defaults the format to JSON.
     * Keeps the calling code clean — no "json" string literal scattered around.
     */
    default List<AviationWeatherAirportDto> getAirportByIcao(String icaoCode) {
        return getAirportByIcao(icaoCode, "json");
    }
}
