package com.aigovernance.dto.response;

import java.time.Instant;

public record AuthResponse(
        String token,
        String userId,
        String email,
        String role,
        Instant expiresApproximately
) {}
