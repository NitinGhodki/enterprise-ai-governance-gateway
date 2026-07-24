package com.aigovernance.dto.response;

import java.util.List;

public record SafetyCheckResponse(
        String requestId,
        boolean isSafe,
        List<String> violations,
        String redactedMessage,
        boolean piiDetected,
        boolean injectionDetected,
        int processingMs
) {}