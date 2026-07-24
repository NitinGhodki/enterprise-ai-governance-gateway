package com.aigovernance.dto.request;

import java.util.List;

public record HfChatRequest(
        String model,
        List<HfMessage> messages,
        int max_tokens,
        double temperature,
        boolean stream
) {}
