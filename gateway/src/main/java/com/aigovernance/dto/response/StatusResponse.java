package com.aigovernance.dto.response;

public record StatusResponse(
        String name,
        String version,
        String status,
        java.util.List<String> providers,
        java.util.Map<String, String> features
) {}