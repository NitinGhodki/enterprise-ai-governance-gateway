package com.aigovernance.dto.response;

public record CompletionResult(
        String content,
        String model,
        String provider,
        int promptTokens,
        int completionTokens,
        long latencyMs
) {}
