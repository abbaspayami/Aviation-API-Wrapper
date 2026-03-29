package com.sporty.aviation.service;

import com.sporty.aviation.client.AirportDbFeignClient;
import com.sporty.aviation.dto.AviationWeatherAirportDto;
import com.sporty.aviation.dto.Provider2AirportDto;
import com.sporty.aviation.exception.AviationApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Provider2AirportService (AirportDB fallback).
 *
 * Verifies:
 *  1. Successful response from Provider 2 is mapped correctly to AviationWeatherAirportDto.
 *  2. Provider 2 failure propagates as AviationApiException.
 *  3. Coordinate strings are parsed to Double correctly.
 *  4. Null/blank coordinates are handled without exceptions.
 */
@ExtendWith(MockitoExtension.class)
class Provider2AirportServiceTest {

    @Mock
    private AirportDbFeignClient airportDbFeignClient;

    @InjectMocks
    private Provider2AirportService provider2AirportService;

    @Test
    void getAirportByIcao_provider2ReturnsData_mapsFieldsCorrectly() {
        when(airportDbFeignClient.getAirportByIcao("EGLL")).thenReturn(buildEgllDto());

        AviationWeatherAirportDto result = provider2AirportService.getAirportByIcao("EGLL");

        assertThat(result.getIcaoId()).isEqualTo("EGLL");
        assertThat(result.getIataId()).isEqualTo("LHR");
        assertThat(result.getName()).isEqualTo("Heathrow Airport");
        assertThat(result.getCountry()).isEqualTo("GB");
        assertThat(result.getState()).isEqualTo("GB-ENG");
        assertThat(result.getLat()).isEqualTo(51.4775);
        assertThat(result.getLon()).isEqualTo(-0.461389);
        assertThat(result.getElev()).isEqualTo(83);
        assertThat(result.getType()).isEqualTo("large_airport");
    }

    @Test
    void getAirportByIcao_provider2Fails_propagatesException() {
        when(airportDbFeignClient.getAirportByIcao("EGLL"))
                .thenThrow(new AviationApiException("Provider 2 returned HTTP 503"));

        assertThatThrownBy(() -> provider2AirportService.getAirportByIcao("EGLL"))
                .isInstanceOf(AviationApiException.class)
                .hasMessageContaining("503");
    }

    @Test
    void getAirportByIcao_nullCoordinates_doesNotThrow() {
        Provider2AirportDto dto = buildEgllDto();
        dto.setLatitudeDeg(null);
        dto.setLongitudeDeg(null);

        when(airportDbFeignClient.getAirportByIcao("EGLL")).thenReturn(dto);

        AviationWeatherAirportDto result = provider2AirportService.getAirportByIcao("EGLL");

        assertThat(result.getLat()).isNull();
        assertThat(result.getLon()).isNull();
    }

    @Test
    void getAirportByIcao_invalidCoordinateString_returnsNull() {
        Provider2AirportDto dto = buildEgllDto();
        dto.setLatitudeDeg("not-a-number");

        when(airportDbFeignClient.getAirportByIcao("EGLL")).thenReturn(dto);

        AviationWeatherAirportDto result = provider2AirportService.getAirportByIcao("EGLL");

        assertThat(result.getLat()).isNull();
    }

    // -------------------------------------------------------------------------
    // Test data builder
    // -------------------------------------------------------------------------

    private Provider2AirportDto buildEgllDto() {
        Provider2AirportDto dto = new Provider2AirportDto();
        dto.setIcao("EGLL");
        dto.setIata("LHR");
        dto.setName("Heathrow Airport");
        dto.setType("large_airport");
        dto.setLatitudeDeg("51.4775");
        dto.setLongitudeDeg("-0.461389");
        dto.setElevationFt(83);
        dto.setIsoCountry("GB");
        dto.setIsoRegion("GB-ENG");
        dto.setMunicipality("London");
        return dto;
    }
}
