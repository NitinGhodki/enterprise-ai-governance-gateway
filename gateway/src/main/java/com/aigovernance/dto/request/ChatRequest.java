package com.aigovernance.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound chat request from API consumers.
 * provider: optional — "huggingface" | "ollama". Defaults to configured default.
 */
public record ChatRequest(

        @NotBlank(message = "message must not be blank")
        @Size(max = 8000, message = "message must not exceed 8000 characters")
        String message,

        String systemPrompt,   // optional system prompt

        String provider,       // optional: "huggingface" | "ollama"

        @Size(max = 100)
        String model           // optional model override
) {}