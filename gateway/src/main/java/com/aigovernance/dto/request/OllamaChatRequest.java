package com.aigovernance.dto.request;

import java.util.List;

public record OllamaChatRequest(
        String model,
        List<OllamaMessage> messages,
        boolean stream
) {}
