package com.sporty.aviation.service;

import com.sporty.aviation.client.AviationWeatherFeignClient;
import com.sporty.aviation.dto.AirportResponse;
import com.sporty.aviation.dto.AviationWeatherAirportDto;
import com.sporty.aviation.exception.AirportNotFoundException;
import com.sporty.aviation.service.impl.AirportServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AirportServiceImpl — primary API path only.
 * Resilience4j annotations (@CircuitBreaker, @Retry) are Spring AOP proxies
 * and do NOT activate in plain unit tests. Fallback and resilience behaviour
 * is verified separately in Provider2AirportServiceTest and integration tests.
 */
@ExtendWith(MockitoExtension.class)
class AirportServiceTest {

    @Mock
    private AviationWeatherFeignClient aviationWeatherFeignClient;

    @InjectMocks
    private AirportServiceImpl airportService;

    // -------------------------------------------------------------------------
    // Happy path — service returns clean AirportResponse
    // -------------------------------------------------------------------------

    @Test
    void getAirportByIcao_primaryReturnsData_returnsAirportResponse() {
        AviationWeatherAirportDto dto = new AviationWeatherAirportDto();
        dto.setIcaoId("KJFK");
        dto.setIataId("JFK");
        dto.setName("NEW YORK/JOHN F KENNEDY INTL");
        dto.setCountry("US");
        dto.setLat(40.6398);
        dto.setLon(-73.7789);
        dto.setElev(13);

        when(aviationWeatherFeignClient.getAirportByIcao("KJFK")).thenReturn(List.of(dto));

        AirportResponse result = airportService.getAirportByIcao("KJFK");

        assertThat(result.getIcao()).isEqualTo("KJFK");
        assertThat(result.getIata()).isEqualTo("JFK");
        assertThat(result.getName()).isEqualTo("NEW YORK/JOHN F KENNEDY INTL");
        assertThat(result.getCountry()).isEqualTo("US");
        assertThat(result.getLatitude()).isEqualTo(40.6398);
        assertThat(result.getLongitude()).isEqualTo(-73.7789);
        assertThat(result.getElevationFt()).isEqualTo(13);
        assertThat(result.getTimezone()).isNull();   // not provided by upstream
    }

    @Test
    void getAirportByIcao_primaryReturnsEmptyList_throwsAirportNotFoundException() {
        when(aviationWeatherFeignClient.getAirportByIcao("ZZZZ"))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> airportService.getAirportByIcao("ZZZZ"))
                .isInstanceOf(AirportNotFoundException.class)
                .hasMessageContaining("ZZZZ");
    }

    // -------------------------------------------------------------------------
    // Bug 1 — ICAO code must be uppercased before the API call
    // -------------------------------------------------------------------------

    @Test
    void getAirportByIcao_lowercaseInput_sendsUppercaseToFeignClient() {
        // The @Cacheable key is already normalised to uppercase via
        // #icaoCode.toUpperCase(). The Feign call must also send uppercase
        // so cache keys and API calls are always consistent.
        AviationWeatherAirportDto dto = new AviationWeatherAirportDto();
        dto.setIcaoId("EGLL");

        when(aviationWeatherFeignClient.getAirportByIcao("EGLL")).thenReturn(List.of(dto));

        // Call with lowercase — the service must uppercase it internally
        AirportResponse result = airportService.getAirportByIcao("egll");

        assertThat(result.getIcao()).isEqualTo("EGLL");
        // Verify the Feign client received "EGLL", not "egll"
        verify(aviationWeatherFeignClient).getAirportByIcao("EGLL");
    }

    // -------------------------------------------------------------------------
    // Mapping — city is populated when municipality is set in the raw DTO
    // -------------------------------------------------------------------------

    @Test
    void getAirportByIcao_withMunicipality_populatesCityInResponse() {
        AviationWeatherAirportDto dto = new AviationWeatherAirportDto();
        dto.setIcaoId("EGLL");
        dto.setMunicipality("London");

        when(aviationWeatherFeignClient.getAirportByIcao("EGLL")).thenReturn(List.of(dto));

        AirportResponse result = airportService.getAirportByIcao("EGLL");

        assertThat(result.getCity()).isEqualTo("London");
    }

    @Test
    void getAirportByIcao_withoutMunicipality_cityIsNull() {
        AviationWeatherAirportDto dto = new AviationWeatherAirportDto();
        dto.setIcaoId("EGLL");
        // municipality not set — primary API doesn't provide it

        when(aviationWeatherFeignClient.getAirportByIcao("EGLL")).thenReturn(List.of(dto));

        AirportResponse result = airportService.getAirportByIcao("EGLL");

        assertThat(result.getCity()).isNull();
    }
}
