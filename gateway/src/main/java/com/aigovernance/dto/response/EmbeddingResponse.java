package com.aigovernance.dto.response;

public record EmbeddingResponse(
        float[] embedding,
        int dimensions,
        String model
) {}