package com.aigovernance.dto.request;

import java.util.List;

public record QualityCheckRequest(
        String requestId,
        String userId,
        String originalMessage,
        String llmResponse,
        List<String> contextDocuments,
        String provider,
        String model
) {}