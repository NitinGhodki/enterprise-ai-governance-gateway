package com.aigovernance.dto.response;

import com.aigovernance.dto.GovernanceReport;

import java.time.Instant;

/**
 * Outbound response returned to API consumers.
 * Includes governance metadata so consumers can see
 * what quality checks their request passed through.
 */
public record ChatResponse(
        String requestId,
        String answer,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        double estimatedCostUsd,
        long latencyMs,
        boolean cacheHit,
        GovernanceReport governance,
        Instant timestamp
) {}