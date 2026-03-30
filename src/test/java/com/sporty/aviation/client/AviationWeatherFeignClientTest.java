package com.sporty.aviation.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sporty.aviation.dto.AviationWeatherAirportDto;
import com.sporty.aviation.exception.AviationApiException;
import com.sporty.aviation.exception.AviationClientException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link AviationWeatherFeignClient}.
 *
 * <p>Uses WireMock to stub the real Aviation Weather API so no actual HTTP
 * call leaves the JVM. The stub responses mirror the real API's JSON shape.
 *
 * <p>{@code @SpringBootTest} loads the full Spring context so we can assert
 * that Feign is wired correctly end-to-end (URL, JSON deserialisation, error
 * decoder). The Aviation Weather base URL is overridden via
 * {@code @DynamicPropertySource} to point at the WireMock server.
 */
@SpringBootTest
class AviationWeatherFeignClientTest {

    // One WireMock server shared across all tests in this class
    static WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.options().dynamicPort()
    );

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    /**
     * Redirects the Feign client's base URL to WireMock before the
     * Spring context is created (required for @FeignClient url resolution).
     */
    @DynamicPropertySource
    static void overrideBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("aviation.api.base-url", wireMock::baseUrl);
    }

    @Autowired
    private AviationWeatherFeignClient feignClient;

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void getAirportByIcao_validIcao_deserializesAllFields() {
        wireMock.stubFor(get(urlPathEqualTo("/airport"))
                .withQueryParam("ids",    equalTo("KJFK"))
                .withQueryParam("format", equalTo("json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {
                                    "icaoId":    "KJFK",
                                    "iataId":    "JFK",
                                    "faaId":     "JFK",
                                    "name":      "NEW YORK/JOHN F KENNEDY INTL ",
                                    "state":     "NY",
                                    "country":   "US",
                                    "lat":       40.6399,
                                    "lon":       -73.7787,
                                    "elev":      4,
                                    "magdec":    "13W",
                                    "owner":     "P",
                                    "rwyNum":    "4",
                                    "tower":     "T",
                                    "beacon":    "B",
                                    "passengers":"55280",
                                    "freqs":     "D-ATIS,115.4;LCL/P,119.1",
                                    "runways": [
                                      {"id":"04L/22R","dimension":"12079x200","surface":"C","alignment":31}
                                    ]
                                  }
                                ]
                                """)));

        List<AviationWeatherAirportDto> results = feignClient.getAirportByIcao("KJFK");

        // The API always returns a list — even for one ICAO code
        assertThat(results).hasSize(1);

        AviationWeatherAirportDto airport = results.get(0);
        assertThat(airport.getIcaoId()).isEqualTo("KJFK");
        assertThat(airport.getIataId()).isEqualTo("JFK");
        assertThat(airport.getName()).contains("KENNEDY");
        assertThat(airport.getLat()).isEqualTo(40.6399);
        assertThat(airport.getLon()).isEqualTo(-73.7787);
        assertThat(airport.getElev()).isEqualTo(4);
        assertThat(airport.getTower()).isEqualTo("T");
        assertThat(airport.getRunways()).hasSize(1);
        assertThat(airport.getRunways().get(0).getId()).isEqualTo("04L/22R");
        assertThat(airport.getRunways().get(0).getDimension()).isEqualTo("12079x200");
    }

    @Test
    void getAirportByIcao_unknownIcao_returnsEmptyList() {
        wireMock.stubFor(get(urlPathEqualTo("/airport"))
                .withQueryParam("ids", equalTo("ZZZZ"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));           // Real API returns [] for unknown codes

        List<AviationWeatherAirportDto> results = feignClient.getAirportByIcao("ZZZZ");
        assertThat(results).isEmpty();
    }

    @Test
    void getAirportByIcao_apiReturns500_throwsAviationApiException() {
        wireMock.stubFor(get(urlPathEqualTo("/airport"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> feignClient.getAirportByIcao("KJFK"))
                .isInstanceOf(AviationApiException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void getAirportByIcao_apiReturns429_throwsAviationClientException() {
        wireMock.stubFor(get(urlPathEqualTo("/airport"))
                .willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> feignClient.getAirportByIcao("KJFK"))
                .isInstanceOf(AviationClientException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void getAirportByIcao_apiReturns404_throwsAviationClientException() {
        // 404 is a permanent client error → AviationClientException, never retried.
        wireMock.stubFor(get(urlPathEqualTo("/airport"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> feignClient.getAirportByIcao("KJFK"))
                .isInstanceOf(AviationClientException.class)
                .hasMessageContaining("not found");
    }
}
