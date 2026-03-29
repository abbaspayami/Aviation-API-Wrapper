package com.sporty.aviation.service;

import com.sporty.aviation.dto.AviationWeatherAirportDto;

/**
 * Contract for fallback airport data providers.
 *
 * <p>When the primary Aviation Weather API is unavailable, {@code AirportServiceImpl}
 * delegates to an implementation of this interface. Adding a new fallback provider
 * (e.g. Provider 3) requires only a new implementation class — the caller never changes.
 */
public interface FallbackAirportService {

    /**
     * Fetches airport details by ICAO code from the fallback provider.
     *
     * @param icaoCode the 4-letter ICAO airport identifier (e.g. "EGLL")
     * @return airport details mapped to the shared internal DTO
     */
    AviationWeatherAirportDto getAirportByIcao(String icaoCode);
}
