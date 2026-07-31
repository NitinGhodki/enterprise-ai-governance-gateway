package com.aigovernance.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HfChatResponse(
        String id,
        List<Choice> choices,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Message message,
            @JsonProperty("finish_reason")
            String finish_reason
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Message(
                String role,
                String content
        ) {}
    }

    public record Usage(
            @JsonProperty("prompt_tokens")
            int promptTokens,
            @JsonProperty("completion_tokens")
            int completionTokens,
            @JsonProperty("total_tokens")
            int totalTokens
    ) {}
}