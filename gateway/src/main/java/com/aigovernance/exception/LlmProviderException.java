package com.aigovernance.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an LLM provider (HuggingFace, Ollama) returns an error.
 * Results in HTTP 502 Bad Gateway — the gateway received an invalid
 * response from the upstream provider.
 *
 * provider: "huggingFace" | "ollama"
 * upstreamStatus: the HTTP status code returned by the provider
 * upstreamMessage: the error message from the provider
 */
@Getter
public class LlmProviderException extends GatewayException {

    private final String provider;
    private final int upstreamStatus;

    public LlmProviderException(String provider, int upstreamStatus, String upstreamMessage) {
        super(
                HttpStatus.BAD_GATEWAY,
                "LLM_PROVIDER_ERROR",
                String.format("Provider '%s' returned status %d: %s",
                        provider, upstreamStatus, upstreamMessage)
        );
        this.provider = provider;
        this.upstreamStatus = upstreamStatus;
    }

    public LlmProviderException(String provider, String message, Throwable cause) {
        super(
                HttpStatus.BAD_GATEWAY,
                "LLM_PROVIDER_UNREACHABLE",
                String.format("Provider '%s' is unreachable: %s", provider, message),
                cause
        );
        this.provider = provider;
        this.upstreamStatus = 0;
    }

}