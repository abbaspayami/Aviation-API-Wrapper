package com.sporty.aviation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RateLimitFilter.
 *
 * The filter uses a Redis Lua script that atomically increments the request counter
 * and sets its expiry in one round-trip. All Redis interaction is mocked via:
 *   doReturn(count).when(redisTemplate).execute(any(), any(), any())
 *
 * No real Redis instance is needed.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private RateLimitFilter filter;

    private MockHttpServletRequest  request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setup() throws Exception {
        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "test-key");

        // Inject the @Value field (not wired by Spring in unit tests)
        ReflectionTestUtils.setField(filter, "requestsPerMinute", 60);
    }

    // -------------------------------------------------------------------------
    // Helper — stub the Lua script execution to return a specific counter value
    // -------------------------------------------------------------------------

    private void stubLuaScript(long count) {
        doReturn(count).when(redisTemplate).execute(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Under the limit — request passes through
    // -------------------------------------------------------------------------

    @Test
    void firstRequest_underLimit_callsNextFilter() throws Exception {
        stubLuaScript(1L);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void luaScript_calledOncePerRequest() throws Exception {
        // The Lua script (INCR + EXPIRE) must be executed exactly once per request
        // — both commands run atomically in a single round-trip.
        stubLuaScript(1L);

        filter.doFilter(request, response, filterChain);

        verify(redisTemplate, times(1)).execute(any(), any(), any());
        // The separate expire() method must never be called — it is now inside the Lua script.
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void subsequentRequest_luaScriptStillCalledOnce() throws Exception {
        // Count = 5 simulates the 5th request in the same window.
        // The Lua script handles INCR internally, so the filter still calls execute() once.
        stubLuaScript(5L);

        filter.doFilter(request, response, filterChain);

        verify(redisTemplate, times(1)).execute(any(), any(), any());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void underLimit_addsRateLimitHeaders() throws Exception {
        stubLuaScript(10L);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("50");
    }

    @Test
    void exactlyAtLimit_stillPassesThrough() throws Exception {
        stubLuaScript(60L);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    // -------------------------------------------------------------------------
    // Over the limit — 429 returned
    // -------------------------------------------------------------------------

    @Test
    void overLimit_returns429() throws Exception {
        stubLuaScript(61L);
        when(redisTemplate.getExpire("rate_limit:test-key", TimeUnit.SECONDS)).thenReturn(45L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":429}");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void overLimit_addsRetryAfterHeader() throws Exception {
        stubLuaScript(61L);
        when(redisTemplate.getExpire("rate_limit:test-key", TimeUnit.SECONDS)).thenReturn(42L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":429}");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
    }

    @Test
    void overLimit_rateHeaderRemainingIsZero() throws Exception {
        stubLuaScript(100L);
        when(redisTemplate.getExpire(anyString(), any())).thenReturn(30L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":429}");

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    // -------------------------------------------------------------------------
    // Redis unavailable — fail-open (prefer availability over strict rate limiting)
    // -------------------------------------------------------------------------

    @Test
    void redisUnavailable_failOpen_requestPassesThrough() throws Exception {
        // execute() returns null when Redis is down
        doReturn(null).when(redisTemplate).execute(any(), any(), any());

        filter.doFilter(request, response, filterChain);

        // Fail-open: request is allowed rather than blocking everything on Redis outage
        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // -------------------------------------------------------------------------
    // Bypass paths — actuator, swagger, api-docs skip rate limiting entirely
    // -------------------------------------------------------------------------

    @Test
    void actuatorPath_skipsRateLimitingEntirely() throws Exception {
        request.setRequestURI("/actuator/health");

        filter.doFilter(request, response, filterChain);

        // No Redis calls at all for actuator paths
        verify(redisTemplate, never()).execute(any(), any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void swaggerUiPath_skipsRateLimitingEntirely() throws Exception {
        request.setRequestURI("/swagger-ui/index.html");

        filter.doFilter(request, response, filterChain);

        verify(redisTemplate, never()).execute(any(), any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void apiDocsPath_skipsRateLimitingEntirely() throws Exception {
        request.setRequestURI("/v3/api-docs");

        filter.doFilter(request, response, filterChain);

        verify(redisTemplate, never()).execute(any(), any(), any());
        verify(filterChain).doFilter(request, response);
    }
}
