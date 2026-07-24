package com.aigovernance.cache;

import com.aigovernance.dto.CacheEntry;
import com.aigovernance.dto.CacheEntryDto;
import com.aigovernance.dto.ScoredEntry;
import com.aigovernance.governance.GovernanceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * SemanticCacheService — fixed version.
 *
 * Two Redis templates:
 *   cacheRedisTemplate:  ReactiveRedisTemplate<String, CacheEntryDto>
 *                        → stores actual cache entries (question + answer + embedding)
 *
 *   stringRedisTemplate: ReactiveRedisTemplate<String, String>
 *                        → stores the index set (set of cache keys for similarity scan)
 *
 * Why two templates?
 * Redis sets are typed in Spring Data — the value serialiser is fixed
 * at template construction time. You cannot mix CacheEntryDto values
 * and String values in the same template's set operations.
 * The index set contains only String cache keys.
 * The cache entries are stored as JSON CacheEntryDto objects.
 * Separate templates, separate serialisers, no ClassCastException.
 */
@Slf4j
@Service
public class SemanticCacheService {

    private static final String CACHE_KEY_PREFIX = "semantic_cache:";
    private static final String CACHE_INDEX_KEY  = "semantic_cache:__index__";

    private final ReactiveRedisTemplate<String, CacheEntryDto> cacheRedisTemplate;
    private final ReactiveRedisTemplate<String, String>        indexRedisTemplate;
    private final GovernanceClient governanceClient;
    private final double similarityThreshold;
    private final Duration cacheTtl;

    public SemanticCacheService(
            @Qualifier("cacheRedisTemplate")
            ReactiveRedisTemplate<String, CacheEntryDto> cacheRedisTemplate,

            @Qualifier("stringRedisTemplate")
            ReactiveRedisTemplate<String, String> indexRedisTemplate,

            GovernanceClient governanceClient,

            @Value("${gateway.cache.similarity-threshold}") double similarityThreshold,
            @Value("${gateway.cache.ttl-hours}")            long   ttlHours) {

        this.cacheRedisTemplate   = cacheRedisTemplate;
        this.indexRedisTemplate   = indexRedisTemplate;
        this.governanceClient     = governanceClient;
        this.similarityThreshold  = similarityThreshold;
        this.cacheTtl             = Duration.ofHours(ttlHours);

        log.info("SemanticCacheService ready: threshold={} ttl={}h",
                similarityThreshold, ttlHours);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Look up a query in the semantic cache.
     * Tries exact SHA-256 match first, then cosine similarity scan.
     * Returns Mono.empty() on cache miss.
     */
    public Mono<CacheEntry> lookup(String query) {
        String exactKey = cacheKey(query);

        return cacheRedisTemplate.opsForValue()
                .get(exactKey)
                .map(this::toCacheEntry)
                .switchIfEmpty(semanticLookup(query))
                .doOnNext(hit -> log.info("Cache HIT query={}",
                        query.substring(0, Math.min(40, query.length()))));
    }

    /**
     * Store a response in the cache.
     *
     * Two writes:
     *   1. cacheRedisTemplate: key → CacheEntryDto (JSON, TTL applied)
     *   2. indexRedisTemplate:  CACHE_INDEX_KEY set ← key (String, no TTL —
     *      stale index keys do no harm; lookup misses on absent cache keys)
     */
    public Mono<Void> store(
            String  query,
            String  answer,
            float[] embedding,
            String  provider,
            String  model,
            int     promptTokens,
            int     completionTokens) {

        String key = cacheKey(query);

        CacheEntryDto entry = new CacheEntryDto(
                query, answer, embedding,
                provider, model,
                System.currentTimeMillis(),
                promptTokens, completionTokens
        );

        Mono<Void> storeEntry = cacheRedisTemplate.opsForValue()
                .set(key, entry, cacheTtl)
                .then();

        // indexRedisTemplate is ReactiveRedisTemplate<String, String>
        // so .opsForSet().add(String key, String... values) is correctly typed
        Mono<Void> updateIndex = indexRedisTemplate.opsForSet()
                .add(CACHE_INDEX_KEY, key)   // ← both operands are String — correct
                .then();

        return storeEntry
                .then(updateIndex)
                .doOnSuccess(v -> log.debug("Cache STORE key={}...",
                        key.substring(0, Math.min(16, key.length()))));
    }

    /**
     * Embed a query via the Python governance service.
     * Returns empty array on failure — causes cache miss, proceeds to LLM.
     */
    public Mono<float[]> getEmbedding(String query) {
        return governanceClient.getEmbedding(query);
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private Mono<CacheEntry> semanticLookup(String query) {
        return governanceClient.getEmbedding(query)
                .filter(emb -> emb.length > 0)   // skip if embedding failed
                .flatMap(queryEmbedding ->
                        // indexRedisTemplate<String,String> — members() returns Flux<String>
                        indexRedisTemplate.opsForSet()
                                .members(CACHE_INDEX_KEY)
                                .flatMap(key ->
                                        cacheRedisTemplate.opsForValue()
                                                .get(key)
                                                .map(entry -> new ScoredEntry(
                                                        entry,
                                                        cosineSimilarity(
                                                                queryEmbedding,
                                                                entry.embedding()
                                                        )
                                                ))
                                )
                                .filter(s -> s.score() >= similarityThreshold)
                                .sort((a, b) -> Double.compare(b.score(), a.score()))
                                .next()
                                .map(s -> {
                                    log.info("Semantic similarity hit score={}",
                                            String.format("%.4f", s.score()));
                                    return toCacheEntry(s.entry());
                                })
                )
                .onErrorResume(e -> {
                    log.warn("Semantic lookup failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }

    private String cacheKey(String query) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(query.toLowerCase().trim().getBytes());
            return CACHE_KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private CacheEntry toCacheEntry(CacheEntryDto dto) {
        return new CacheEntry(dto.answer(), dto.provider(), dto.model(),
                dto.promptTokens(), dto.completionTokens());
    }

}