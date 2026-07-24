package com.aigovernance.dto;

public record ScoredEntry(
        CacheEntryDto entry,
        double score
) {}