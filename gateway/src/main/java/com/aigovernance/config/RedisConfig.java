package com.aigovernance.config;

import com.aigovernance.dto.CacheEntryDto;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * RedisConfig — configures two Redis clients:
 *
 * 1. ReactiveRedisTemplate (Spring Data Reactive) — for semantic cache.
 *    Uses JSON serialisation for cache entries.
 *    Non-blocking, integrates with WebFlux reactive pipeline.
 *
 * 2. RedisClient (Lettuce synchronous) — for Bucket4j rate limiting.
 *    Bucket4j requires synchronous Lettuce for its CAS operations.
 *    We handle the blocking on boundedElastic in RateLimitService.
 *
 * Why two clients for the same Redis instance?
 * ReactiveRedisTemplate uses a reactive connection pool.
 * Bucket4j requires a StatefulRedisConnection with ByteArrayCodec.
 * They cannot share the same connection type — separate clients,
 * same Redis server.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * Lettuce RedisClient for Bucket4j synchronous rate limit operations.
     * This is the synchronous client — used only from boundedElastic threads.
     */
    @Bean
    public RedisClient lettuceRedisClient() {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(redisHost)
                .withPort(redisPort)
                .withTimeout(Duration.ofSeconds(5));

        if (redisPassword != null && !redisPassword.isBlank()) {
            builder.withPassword(redisPassword.toCharArray());
        }

        return RedisClient.create(builder.build());
    }

    /**
     * ReactiveRedisTemplate for semantic cache — JSON-serialised CacheEntry objects.
     */
    @Bean
    public ReactiveRedisTemplate<String, CacheEntryDto> cacheRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {

        var keySerializer = new StringRedisSerializer();
        var valueSerializer = new Jackson2JsonRedisSerializer<>(CacheEntryDto.class);

        var context = RedisSerializationContext
                .<String, CacheEntryDto>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    @Bean("stringRedisTemplate")
    public ReactiveRedisTemplate<String, String> stringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {

        var serializer = new StringRedisSerializer();
        var context = RedisSerializationContext
                .<String, String>newSerializationContext(serializer)
                .value(serializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

}