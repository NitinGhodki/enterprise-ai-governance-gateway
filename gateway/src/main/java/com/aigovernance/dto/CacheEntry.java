package com.aigovernance.dto;

public record CacheEntry(
        String answer,
        String provider,
        String model,
        int promptTokens,
        int completionTokens
) {}