package com.aigovernance.ratelimit;

import com.aigovernance.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * RateLimitService — per-user token bucket rate limiting via Redis.
 *
 * Uses Bucket4j distributed mode with Lettuce (Redis client).
 * Each user gets their own bucket stored as a Redis key:
 *   rate_limit:{userId} → serialised bucket state
 *
 * Token bucket parameters:
 *   capacity:       requestsPerMinute (default: 5)
 *   refill rate:    requestsPerMinute tokens per 60 seconds (greedy)
 *   burst capacity: additional burst tokens above base capacity
 *
 * Atomic operation: Bucket4j uses a Lua CAS (compare-and-swap) script
 * in Redis. The check-and-decrement is atomic — no race condition
 * even with 100 concurrent requests from the same user.
 *
 * Why Schedulers.boundedElastic():
 * Bucket4j's Redis operations use synchronous Lettuce commands.
 * We wrap them in Mono.fromCallable() and publish on boundedElastic
 * to keep the Netty event loop free.
 */
@Slf4j
@Service
public class RateLimitService {

    private final ProxyManager<byte[]> proxyManager;
    private final BucketConfiguration bucketConfiguration;

    public RateLimitService(
            RedisClient redisClient,
            @Value("${gateway.rate-limit.requests-per-minute}") int requestsPerMinute,
            @Value("${gateway.rate-limit.burst-capacity}") int burstCapacity) {

        // Lettuce connection for Bucket4j — separate from reactive connection pool
        StatefulRedisConnection<byte[], byte[]> connection =
                redisClient.connect(ByteArrayCodec.INSTANCE);

        this.proxyManager = LettuceBasedProxyManager.builderFor(connection).build();

        // Token bucket: refills requestsPerMinute tokens per minute
        // Burst: allows burstCapacity extra requests before throttling
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute + burstCapacity)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();

        this.bucketConfiguration = BucketConfiguration.builder()
                .addLimit(limit)
                .build();

        log.info("RateLimitService initialised: {}rpm, burst={}",
                requestsPerMinute, burstCapacity);
    }

    /**
     * Check and consume one token from the user's bucket.
     * Returns Mono.empty() if allowed.
     * Returns Mono.error(RateLimitExceededException) if exceeded.
     *
     * Runs on boundedElastic scheduler to avoid blocking event loop.
     */
    public Mono<Void> checkRateLimit(String userId) {
        return Mono.fromCallable(() -> tryConsume(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(probe -> {
                    if (probe.isConsumed()) {
                        log.debug("Rate limit OK for user={} remaining={}",
                                userId, probe.getRemainingTokens());
                        return Mono.<Void>empty();
                    }

                    long retryAfterNanos = probe.getNanosToWaitForRefill();
                    long retryAfterSeconds = retryAfterNanos / 1_000_000_000L + 1;

                    log.warn("Rate limit exceeded for user={} retryAfter={}s",
                            userId, retryAfterSeconds);

                    return Mono.error(
                            new RateLimitExceededException(userId, retryAfterSeconds)
                    );
                });
    }

    /**
     * Returns remaining tokens for a user — used by metrics and response headers.
     */
    public Mono<Long> getRemainingTokens(String userId) {
        return Mono.fromCallable(() -> {
                    byte[] key = bucketKey(userId);
                    Supplier<BucketConfiguration> configSupplier = () -> bucketConfiguration;
                    var bucket = proxyManager.builder().build(key, configSupplier);
                    return bucket.getAvailableTokens();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(-1L);
    }

    private ConsumptionProbe tryConsume(String userId) {
        byte[] key = bucketKey(userId);
        Supplier<BucketConfiguration> configSupplier = () -> bucketConfiguration;
        var bucket = proxyManager.builder().build(key, configSupplier);
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    private byte[] bucketKey(String userId) {
        return ("rate_limit:" + userId).getBytes();
    }
}