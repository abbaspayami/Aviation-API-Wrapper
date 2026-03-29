package com.sporty.aviation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiKeyAuthFilter.
 *
 * Uses Spring's MockHttpServletRequest / MockHttpServletResponse so we can
 * assert on the response status and body without starting a server.
 *
 * Note: the objectMapper stub is set up only in tests that exercise the error
 * path (401) — where the filter writes a JSON error body. It is deliberately
 * NOT stubbed in @BeforeEach to avoid UnnecessaryStubbingException for tests
 * that hit the happy path and never write a response body.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    @Mock
    private ApiKeyProperties apiKeyProperties;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private ApiKeyAuthFilter filter;

    private MockHttpServletRequest  request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setup() {
        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        // lenient: bypass-path tests (actuator, swagger, api-docs) skip key validation entirely
        // so this stub is unused for those tests — lenient avoids UnnecessaryStubbingException.
        lenient().when(apiKeyProperties.getApiKeys()).thenReturn(Set.of("valid-key"));
    }

    // -------------------------------------------------------------------------
    // 401 — missing or invalid key
    // -------------------------------------------------------------------------

    @Test
    void missingApiKey_returns401() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":401}");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void emptyApiKey_returns401() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":401}");
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "  ");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void invalidApiKey_returns401() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":401}");
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    // -------------------------------------------------------------------------
    // Pass-through — valid key or excluded path
    // -------------------------------------------------------------------------

    @Test
    void validApiKey_callsNextFilter() throws Exception {
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "valid-key");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        // Default status is 200 — the filter did NOT write an error response
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void actuatorPath_skipsAuthAndCallsNextFilter() throws Exception {
        request.setRequestURI("/actuator/health");
        // No API key header — but actuator is excluded from auth

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void actuatorSubPath_skipsAuth() throws Exception {
        request.setRequestURI("/actuator/circuitbreakers");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Swagger / OpenAPI paths — excluded from auth so docs are always accessible
    // -------------------------------------------------------------------------

    @Test
    void swaggerUiPath_skipsAuthAndCallsNextFilter() throws Exception {
        request.setRequestURI("/swagger-ui/index.html");
        // No API key header — swagger-ui must be accessible without auth

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void apiDocsPath_skipsAuthAndCallsNextFilter() throws Exception {
        request.setRequestURI("/v3/api-docs");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
