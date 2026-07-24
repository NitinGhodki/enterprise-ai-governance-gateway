package com.aigovernance.dto.response;

import java.util.List;

public record HfChatResponse(
        String id,
        List<Choice> choices,
        Usage usage
) {
    public record Choice(
            Message message,
            String finish_reason
    ) {
        public record Message(
                String role,
                String content
        ) {}
    }

    public record Usage(
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {}
}