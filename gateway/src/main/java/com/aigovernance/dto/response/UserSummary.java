package com.aigovernance.dto.response;

import java.time.Instant;

public record UserSummary(
        String  id,
        String  email,
        String  role,
        boolean isActive,
        Instant createdAt
) {}