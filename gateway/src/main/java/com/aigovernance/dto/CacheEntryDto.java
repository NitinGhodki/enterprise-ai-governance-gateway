package com.aigovernance.dto;

/**
 * DTO stored in Redis for each cache entry.
 * Kept package-private — only used by RedisConfig and SemanticCacheService.
 */
public record CacheEntryDto(
        String question,
        String answer,
        float[] embedding,
        String provider,
        String model,
        long createdAt,
        int promptTokens,
        int completionTokens
) {}
