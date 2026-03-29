package com.sporty.aviation.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Makes the cache layer independent from Redis.
 *
 * <p>Spring's default behaviour is to re-throw any exception that occurs during
 * a cache operation. That means a Redis outage causes a 500 error for every
 * request — even though the Aviation Weather API is perfectly healthy.
 *
 * <p>This handler intercepts all four cache operations and, instead of
 * propagating the exception, simply logs a warning and returns normally.
 * Spring then treats the result as a cache miss and continues to the real
 * method (the external API call).
 *
 * <p><b>Behaviour when Redis is down:</b>
 * <ul>
 *   <li>{@code GET  /api/v1/airports/EGLL} — cache miss → calls Aviation Weather API → returns 200</li>
 *   <li>{@code PUT  cache entry}           — silently skipped, no write to Redis</li>
 *   <li>Every subsequent call also bypasses the cache until Redis recovers</li>
 *   <li>When Redis recovers — cache starts working again automatically, no restart needed</li>
 * </ul>
 *
 * <p><b>Behaviour when Redis is up:</b> this handler is never triggered —
 * normal cache read/write/evict flow runs as usual.
 */
@Slf4j
public class RedisCacheErrorHandler implements CacheErrorHandler {

    private static final String MSG = "Redis cache unavailable — operation: {}, cache: {}, key: {}. "
                                    + "Bypassing cache and calling the external API directly.";

    /**
     * Called when reading from the cache fails (e.g. Redis connection refused).
     * Returning normally tells Spring to treat this as a cache miss.
     */
    @Override
    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
        log.warn(MSG, "GET", cache.getName(), key, e);
    }

    /**
     * Called when writing to the cache fails.
     * Returning normally skips the write — the next request will call the API again.
     */
    @Override
    public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
        log.warn(MSG, "PUT", cache.getName(), key, e);
    }

    /**
     * Called when evicting a cache entry fails.
     * Returning normally skips the eviction — stale data may persist until TTL expires.
     */
    @Override
    public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
        log.warn(MSG, "EVICT", cache.getName(), key, e);
    }

    /**
     * Called when clearing the entire cache fails.
     * Returning normally skips the clear.
     */
    @Override
    public void handleCacheClearError(RuntimeException e, Cache cache) {
        log.warn("Redis cache unavailable — operation: CLEAR, cache: {}. Skipping.", cache.getName(), e);
    }
}
