package com.aigovernance.dto.response;

public record CostEstimateResponse(
        int promptTokens,
        int completionTokens,
        int totalTokens,
        double costUsd,
        String provider,
        String model
) {}