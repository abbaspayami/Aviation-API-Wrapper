package com.sporty.aviation.controller;

import com.sporty.aviation.dto.AirportResponse;
import com.sporty.aviation.exception.AirportNotFoundException;
import com.sporty.aviation.security.ApiKeyAuthFilter;
import com.sporty.aviation.security.ApiKeyProperties;
import com.sporty.aviation.service.AirportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AirportController.class)
class AirportControllerTest {

    private static final String VALID_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AirportService airportService;

    // Required by ApiKeyAuthFilter
    @MockBean
    private ApiKeyProperties apiKeyProperties;

    // Required by RateLimitFilter (Lua script path)
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setup() {
        // Auth filter: accept our test key
        when(apiKeyProperties.getApiKeys()).thenReturn(Set.of(VALID_KEY));

        // Rate limit filter: Lua script returns count = 1 (well under the 60 req/min default).
        // execute(RedisScript, List<K>, Object...) is the method called by the filter.
        when(stringRedisTemplate.execute(any(), any(), any())).thenReturn(1L);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void getAirport_validKeyAndIcao_returns200WithCleanResponse() throws Exception {
        AirportResponse response = AirportResponse.builder()
                .icao("EGLL")
                .iata("LHR")
                .name("LONDON HEATHROW")
                .city("London")
                .country("GB")
                .latitude(51.4775)
                .longitude(-0.461389)
                .timezone(null)
                .elevationFt(83)
                .type("large_airport")
                .build();

        when(airportService.getAirportByIcao("EGLL")).thenReturn(response);

        mockMvc.perform(get("/api/v1/airports/EGLL")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icao").value("EGLL"))
                .andExpect(jsonPath("$.iata").value("LHR"))
                .andExpect(jsonPath("$.name").value("LONDON HEATHROW"))
                .andExpect(jsonPath("$.city").value("London"))
                .andExpect(jsonPath("$.country").value("GB"))
                .andExpect(jsonPath("$.latitude").value(51.4775))
                .andExpect(jsonPath("$.longitude").value(-0.461389))
                .andExpect(jsonPath("$.elevationFt").value(83))
                .andExpect(jsonPath("$.type").value("large_airport"));
    }

    // -------------------------------------------------------------------------
    // 401 — API key missing or invalid
    // -------------------------------------------------------------------------

    @Test
    void getAirport_missingApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/airports/EGLL"))   // no X-API-Key header
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void getAirport_invalidApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/airports/EGLL")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // -------------------------------------------------------------------------
    // 429 — rate limit exceeded
    // -------------------------------------------------------------------------

    @Test
    void getAirport_rateLimitExceeded_returns429() throws Exception {
        // Override the @BeforeEach stub: Lua script returns 61 (over the 60 req/min limit)
        when(stringRedisTemplate.execute(any(), any(), any())).thenReturn(61L);
        when(stringRedisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(45L);

        mockMvc.perform(get("/api/v1/airports/EGLL")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(header().string("X-RateLimit-Limit", "60"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    // -------------------------------------------------------------------------
    // 404 — unknown ICAO
    // -------------------------------------------------------------------------

    @Test
    void getAirport_unknownIcao_returns404() throws Exception {
        when(airportService.getAirportByIcao("ZZZZ"))
                .thenThrow(new AirportNotFoundException("ZZZZ"));

        mockMvc.perform(get("/api/v1/airports/ZZZZ")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No airport found for ICAO code: ZZZZ"));
    }

    // -------------------------------------------------------------------------
    // 400 — ICAO validation
    // -------------------------------------------------------------------------

    @Test
    void getAirport_icaoTooShort_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/airports/EG")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAirport_icaoTooLong_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/airports/EGLLX")
                        .header(ApiKeyAuthFilter.API_KEY_HEADER, VALID_KEY))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Swagger / OpenAPI paths — no auth required
    // -------------------------------------------------------------------------

    @Test
    void swaggerUi_noApiKey_isAccessible() throws Exception {
        // Swagger UI must be reachable without authentication.
        // @WebMvcTest does not load SpringDoc, so the path returns 500 (no handler).
        // The important assertion is: NOT 401 — the auth filter bypasses swagger-ui paths.
        int status = mockMvc.perform(get("/swagger-ui/index.html"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
    }

    @Test
    void apiDocs_noApiKey_isAccessible() throws Exception {
        // Same logic: SpringDoc not loaded in @WebMvcTest → not-404 response,
        // but the auth filter must NOT reject it with 401.
        int status = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
    }
}
