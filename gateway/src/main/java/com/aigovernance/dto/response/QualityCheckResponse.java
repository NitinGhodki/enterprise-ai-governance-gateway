package com.aigovernance.dto.response;

import java.util.List;

public record QualityCheckResponse(
        String requestId,
        boolean qualityPassed,
        double faithfulnessScore,
        double relevancyScore,
        double overallScore,
        List<String> failureReasons,
        int processingMs
) {}