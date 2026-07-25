package com.aigovernance.dto;

public record SystemStats(
        Long   totalRequests,
        Long   cacheHits,
        Long   cacheMisses,
        Long   violations,
        Double totalCostUsd,
        Double avgLatencyMs,
        Double p95LatencyMs,
        Long   uniqueUsers,
        Long   requestsLast24h
) {
    public static SystemStats empty() {
        return new SystemStats(0L, 0L, 0L, 0L, 0.0, 0.0, 0.0, 0L, 0L);
    }
}