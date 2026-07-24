package com.aigovernance.dto.request;

public record SafetyCheckRequest(
        String requestId,
        String userId,
        String message,
        String systemPrompt,
        String provider
) {}