package com.aigovernance.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record HfChatRequest(
        String model,
        List<HfMessage> messages,
        @JsonProperty("max_tokens") int max_tokens,
        double temperature,
        boolean stream
) {}
