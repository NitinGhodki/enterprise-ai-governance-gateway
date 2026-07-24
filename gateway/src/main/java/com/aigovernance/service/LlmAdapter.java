package com.aigovernance.service;

import com.aigovernance.dto.request.CompletionRequest;
import com.aigovernance.dto.response.CompletionResult;
import reactor.core.publisher.Mono;

/**
 * LlmAdapter — interface all LLM provider adapters must implement.
 *
 * Two implementations: HuggingFaceAdapter and OllamaAdapter.
 * LlmProxyService selects the correct adapter based on:
 *   1. Request's explicit provider field (if set)
 *   2. gateway.llm.default-provider configuration
 *
 * Adapter contract:
 *   complete() receives a fully-formed CompletionRequest.
 *   Returns Mono<CompletionResult> — never blocks.
 *   Throws LlmProviderException wrapped in Mono.error() on failure.
 *
 * Adding a new provider (e.g. Anthropic):
 *   1. Create AnthropicAdapter implements LlmAdapter
 *   2. Register as @Component
 *   3. Add to LlmProxyService.resolveAdapter()
 *   Zero changes to the rest of the pipeline.
 */
public interface LlmAdapter {

    /**
     * The provider name this adapter handles.
     * Must match values in application.yml and ChatRequest.provider.
     */
    String providerName();

    /**
     * Send a completion request to the LLM provider.
     * Returns CompletionResult containing the response text and token usage.
     */
    Mono<CompletionResult> complete(CompletionRequest request);

    /**
     * Check if the provider is reachable.
     * Used by health indicators and the routing fallback logic.
     */
    Mono<Boolean> isHealthy();


}