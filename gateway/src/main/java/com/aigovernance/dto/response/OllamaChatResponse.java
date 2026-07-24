package com.aigovernance.dto.response;

import java.util.List;

public record OllamaChatResponse(
        List<Choice> choices,
        Usage usage
) {
    public record Choice(
            Message message
    ) {
        public record Message(
                String role,
                String content
        ) {}
    }
    public record Usage(
            int prompt_tokens,
            int completion_tokens
    ) {}
}