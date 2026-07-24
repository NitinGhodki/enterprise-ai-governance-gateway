package com.aigovernance.service.impl.adapters;

import com.aigovernance.dto.request.CompletionRequest;
import com.aigovernance.dto.request.OllamaChatRequest;
import com.aigovernance.dto.request.OllamaMessage;
import com.aigovernance.dto.response.CompletionResult;
import com.aigovernance.dto.response.OllamaChatResponse;
import com.aigovernance.exception.LlmProviderException;
import com.aigovernance.service.LlmAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * OllamaAdapter — calls local Ollama inference server.
 *
 * Ollama exposes an OpenAI-compatible endpoint at:
 * http://localhost:11434/v1/chat/completions
 *
 * Differences from HuggingFace:
 *   No API key required.
 *   Higher timeout (120s — local GPU inference can be slow).
 *   Token usage: Ollama returns eval_count (completion tokens) and
 *   prompt_eval_count — slightly different field names than OpenAI.
 *   Cost is always $0.00 — local inference has no per-token charge.
 *
 * Health check: Ollama exposes GET /api/tags listing available models.
 * We check this endpoint rather than attempting a completion.
 */
@Slf4j
@Component
public class OllamaAdapter implements LlmAdapter {

    private final WebClient webClient;
    private final String   ollamaBaseUrl;
    private final String   defaultModel;

    public OllamaAdapter(
            @Qualifier("llmWebClient") WebClient webClient,
            @Value("${gateway.llm.ollama.base-url}") String ollamaBaseUrl,
            @Value("${gateway.llm.ollama.model}")    String defaultModel) {

        this.webClient     = webClient;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.defaultModel  = defaultModel;
    }

    @Override
    public String providerName() { return "ollama"; }

    @Override
    public Mono<CompletionResult> complete(CompletionRequest request) {
        String model  = (request.model() != null && !request.model().isBlank())
                ? request.model() : defaultModel;
        long startMs  = Instant.now().toEpochMilli();

        List<OllamaMessage> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new OllamaMessage("system", request.systemPrompt()));
        }
        messages.add(new OllamaMessage("user", request.userMessage()));

        OllamaChatRequest body = new OllamaChatRequest(model, List.copyOf(messages), false);

        log.debug("[Ollama] Sending completion requestId={} model={}",
                request.requestId(), model);

        return webClient.post()
                .uri(ollamaBaseUrl + "/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OllamaChatResponse.class)
                .map(response -> {
                    long latencyMs = Instant.now().toEpochMilli() - startMs;
                    String content = String.valueOf(response.choices().get(0));

                    // Ollama v1 OpenAI-compat endpoint returns standard usage object
                    int promptTokens     = response.usage() != null
                            ? response.usage().prompt_tokens() : 0;
                    int completionTokens = response.usage() != null
                            ? response.usage().completion_tokens() : 0;

                    log.info("[Ollama] Done requestId={} latency={}ms",
                            request.requestId(), latencyMs);

                    return new CompletionResult(
                            content, model, providerName(),
                            promptTokens, completionTokens, latencyMs
                    );
                })
                .onErrorMap(WebClientResponseException.class, ex ->
                        new LlmProviderException(providerName(),
                                ex.getStatusCode().value(),
                                ex.getResponseBodyAsString())
                )
                .onErrorMap(
                        e -> !(e instanceof LlmProviderException),
                        e -> new LlmProviderException(
                                providerName(),
                                "Ollama unreachable at " + ollamaBaseUrl
                                        + ": " + e.getMessage(),
                                e)
                );
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return webClient.get()
                .uri(ollamaBaseUrl + "/api/tags")
                .retrieve()
                .toBodilessEntity()
                .map(r -> r.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);
    }





}