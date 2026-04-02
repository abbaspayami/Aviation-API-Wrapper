package com.sporty.aviation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis cache configuration.
 *
 * <p>Implements {@link CachingConfigurer} to register a custom
 * {@link RedisCacheErrorHandler} that makes the cache layer fully optional:
 *
 * <ul>
 *   <li><b>Redis UP</b>   — cache reads and writes work normally (fast path).</li>
 *   <li><b>Redis DOWN</b> — every cache error is logged as a warning and swallowed.
 *       Spring treats it as a cache miss and calls the external API directly.
 *       The system keeps returning correct responses — Redis outage is invisible
 *       to callers. When Redis recovers, caching resumes automatically.</li>
 * </ul>
 *
 * <p>Other decisions:
 * <ul>
 *   <li><b>TTL</b> — every cache entry expires after {@code aviation.cache.ttl-minutes}
 *       (configured in {@code application.yml}). When it expires the next caller
 *       fetches fresh data from the Aviation Weather API and re-populates the cache.</li>
 *   <li><b>Serialisation</b> — values are stored as JSON (not Java binary).
 *       JSON is human-readable in Redis CLI / RedisInsight and survives app restarts
 *       even if the JVM serialVersionUID changes.</li>
 * </ul>
 */
@Configuration
public class RedisConfig implements CachingConfigurer {

    @Value("${aviation.cache.ttl-minutes}")
    private long ttlMinutes;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(ttlMinutes))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * Registers the error handler that silences Redis failures instead of
     * propagating them as 500 errors.
     *
     * <p>Spring calls this method once at startup and uses the returned handler
     * for every {@code @Cacheable}, {@code @CachePut}, and {@code @CacheEvict}
     * operation in the application.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler();
    }
}
