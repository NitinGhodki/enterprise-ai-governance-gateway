package com.aigovernance.dto.request;

public record CostEstimateRequest(
        String promptText,
        String completionText,
        String provider,
        String model
) {}