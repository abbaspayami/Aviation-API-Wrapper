package com.sporty.aviation.service.impl;

import com.sporty.aviation.client.AviationWeatherFeignClient;
import com.sporty.aviation.dto.AirportResponse;
import com.sporty.aviation.dto.AviationWeatherAirportDto;
import com.sporty.aviation.exception.AirportNotFoundException;
import com.sporty.aviation.service.AirportService;
import com.sporty.aviation.service.FallbackAirportService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AirportServiceImpl implements AirportService {

    private final AviationWeatherFeignClient aviationWeatherFeignClient;
    private final FallbackAirportService     fallbackAirportService;

    /**
     * Fetch airport details by ICAO code.
     *
     * Full execution order (outer → inner):
     *
     *  1. @Cacheable       — check Redis first.
     *                        HIT  → return immediately, no HTTP call made.
     *                        MISS → continue down the chain.
     *                        Cache key  : "airports::<UPPER_ICAO>"  (e.g. "airports::EGLL")
     *                        TTL        : aviation.cache.ttl-minutes (default 10 min)
     *
     *  2. @CircuitBreaker  — if the primary API has been failing repeatedly,
     *                        the circuit is OPEN and calls are rejected instantly
     *                        → fallbackMethod "fetchFromProvider2" is invoked.
     *                        When CLOSED, the call proceeds to the retry layer.
     *
     *  3. @Retry           — on a transient failure from the primary API,
     *                        retry up to 3 times with exponential backoff: 1s → 2s → 4s.
     *                        After all retries fail the exception bubbles up to
     *                        the Circuit Breaker, which records it as a failure.
     *
     *  Timeout             — configured in application.yml (spring.cloud.openfeign.client.config.default):
     *                        3s connect / 10s read.
     *
     *  Fallback            — triggered whenever the CB is OPEN *or* the primary API
     *                        throws after all retries are exhausted:
     *                        → delegates to FallbackAirportService (currently AirportDB.io)
     *                        which has its own independent CB + Retry.
     *                        If Provider 2 also fails, the exception propagates to
     *                        GlobalExceptionHandler → 502 / 503 response.
     *
     *  Mapping             — the raw upstream DTO is mapped to the clean public
     *                        AirportResponse before caching, so the cache always
     *                        holds the final response shape.
     */
    @Cacheable(value = "airports", key = "#icaoCode.toUpperCase()")
    @CircuitBreaker(name = "aviationApi", fallbackMethod = "fetchFromProvider2")
    @Retry(name = "aviationApi")
    @Override
    public AirportResponse getAirportByIcao(String icaoCode) {
        log.info("Cache miss — fetching from primary API (aviationweather.gov) for ICAO: {}", icaoCode);

        // Bug 1 fix: normalise to uppercase before sending to the external API.
        // The cache key already uses #icaoCode.toUpperCase() via @Cacheable, so
        // "egll" and "EGLL" hit the same cache entry — the API call must match.
        AviationWeatherAirportDto raw = aviationWeatherFeignClient
                .getAirportByIcao(icaoCode.toUpperCase())
                .stream()
                .findFirst()
                .orElseThrow(() -> new AirportNotFoundException(icaoCode));

        return AirportResponse.from(raw);
    }

    // -----------------------------------------------------------------------
    // Fallback — triggered when the primary API is:
    //   • Timing out      (connect timeout 3s / read timeout 10s → IOException)
    //   • Returning 5xx   (AviationApiException from AviationErrorDecoder)
    //   • Circuit OPEN    (CallNotPermittedException after minimum-number-of-calls=3)
    //
    // The fallback result IS cached by @Cacheable above — so a successful
    // Provider 2 response is stored in Redis and future calls are served from
    // cache without hitting either provider again until TTL expires.
    // -----------------------------------------------------------------------
    private AirportResponse fetchFromProvider2(String icaoCode, Throwable cause) {
        log.warn("Primary API unavailable [{}] — routing to Provider 2 (AirportDB) for ICAO: {}",
                cause.getClass().getSimpleName(), icaoCode);

        AviationWeatherAirportDto raw = fallbackAirportService.getAirportByIcao(icaoCode);
        return AirportResponse.from(raw);
    }
}
