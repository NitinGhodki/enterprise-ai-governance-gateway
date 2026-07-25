package com.aigovernance.dto.response;

import java.time.Instant;

public record ViolationSummary(
        String  requestId,
        String  userId,
        String  email,
        String  safetyFlags,
        Double  governanceScore,
        Instant createdAt
) {}