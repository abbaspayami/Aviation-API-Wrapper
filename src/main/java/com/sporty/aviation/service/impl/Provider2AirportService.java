package com.sporty.aviation.service.impl;

import com.sporty.aviation.client.AirportDbFeignClient;
import com.sporty.aviation.dto.AviationWeatherAirportDto;
import com.sporty.aviation.dto.Provider2AirportDto;
import com.sporty.aviation.service.FallbackAirportService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fallback airport lookup via Provider 2 (AirportDB.io).
 *
 * <p>Implements {@link FallbackAirportService} so that {@code AirportServiceImpl}
 * depends on the interface, not this concrete class. A future Provider 3 can be
 * added by creating another {@link FallbackAirportService} implementation without
 * touching the caller.
 *
 * <p>Why a separate Spring component?
 * Resilience4j annotations ({@code @CircuitBreaker}, {@code @Retry}) work via
 * Spring AOP proxies. AOP only intercepts calls made <em>through</em> a proxy —
 * that is, calls on a Spring-managed bean from another bean. A private method
 * inside the same class is NOT proxied, so annotations on it are ignored.
 * Extracting Provider 2 into its own {@code @Service} ensures its circuit
 * breaker and retry policies are actually enforced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Provider2AirportService implements FallbackAirportService {

    private final AirportDbFeignClient airportDbFeignClient;

    /**
     * Fetches airport details from Provider 2 with its own independent
     * Circuit Breaker and Retry configured under the "provider2Api" instance.
     *
     * <p>If Provider 2 is also unavailable, the exception propagates up to
     * {@link com.sporty.aviation.exception.GlobalExceptionHandler}, which
     * returns an appropriate HTTP error response to the caller.
     *
     * @param icaoCode the 4-letter ICAO airport identifier
     * @return airport details mapped to the same shared DTO used by the primary client
     */
    @CircuitBreaker(name = "provider2Api")
    @Retry(name = "provider2Api")
    @Override
    public AviationWeatherAirportDto getAirportByIcao(String icaoCode) {
        log.info("Fetching from Provider 2 (AirportDB) for ICAO: {}", icaoCode);

        Provider2AirportDto dto = airportDbFeignClient.getAirportByIcao(icaoCode);
        return mapToAviationDto(dto);
    }

    /**
     * Maps the Provider 2 response structure to the shared {@link AviationWeatherAirportDto}.
     *
     * <p>Both providers describe the same real-world airport so we populate the
     * same DTO fields — callers never need to know which provider served the data.
     */
    private AviationWeatherAirportDto mapToAviationDto(Provider2AirportDto dto) {
        AviationWeatherAirportDto result = new AviationWeatherAirportDto();
        result.setIcaoId(dto.getIcao());
        result.setIataId(dto.getIata());
        result.setName(dto.getName());
        result.setMunicipality(dto.getMunicipality());   // city — only available from Provider 2
        result.setCountry(dto.getIsoCountry());
        result.setState(dto.getIsoRegion());
        result.setLat(dto.getLatitudeDeg());
        result.setLon(dto.getLongitudeDeg());
        result.setElev(dto.getElevationFt());
        result.setType(dto.getType());
        return result;
    }
}
