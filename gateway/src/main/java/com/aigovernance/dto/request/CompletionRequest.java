package com.aigovernance.dto.request;

public record CompletionRequest(
        String model,
        String systemPrompt,
        String userMessage,
        int maxTokens,
        double temperature,
        String requestId
) {}