package com.sporty.aviation.service;

import com.sporty.aviation.dto.AirportResponse;

public interface AirportService {

    /**
     * Returns clean, documented airport details for the given ICAO code.
     *
     * <p>Results are cached in Redis for {@code aviation.cache.ttl-minutes} (default 10 min).
     * The primary data source is Aviation Weather (NOAA); if unavailable, the service
     * automatically falls back to AirportDB (Provider 2).
     *
     * @param icaoCode 4-character ICAO identifier — case-insensitive
     * @return the clean {@link AirportResponse} for the requested airport
     * @throws com.sporty.aviation.exception.AirportNotFoundException if no airport matches the code
     */
    AirportResponse getAirportByIcao(String icaoCode);
}
