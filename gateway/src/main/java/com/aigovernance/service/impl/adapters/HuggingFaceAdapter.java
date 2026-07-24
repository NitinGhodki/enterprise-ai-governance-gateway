package com.aigovernance.service.impl.adapters;

import com.aigovernance.dto.request.CompletionRequest;
import com.aigovernance.dto.request.HfChatRequest;
import com.aigovernance.dto.request.HfMessage;
import com.aigovernance.dto.response.CompletionResult;
import com.aigovernance.dto.response.HfChatResponse;
import com.aigovernance.exception.LlmProviderException;
import com.aigovernance.service.LlmAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * HuggingFaceAdapter — calls HuggingFace Inference API.
 *
 * HuggingFace exposes an OpenAI-compatible endpoint at:
 * https://api-inference.huggingface.co/v1/chat/completions
 *
 * Request format: OpenAI ChatCompletion format
 * Response format: OpenAI ChatCompletion response format
 *
 * Token usage: HuggingFace returns usage.prompt_tokens and
 * usage.completion_tokens in the response. We use these values
 * directly — more accurate than our tiktoken estimate.
 * If usage is missing (some models): fall back to tiktoken estimate
 * via the governance cost service.
 */
@Slf4j
@Component
public class HuggingFaceAdapter implements LlmAdapter {

    private final WebClient webClient;
    private final String apiKey;
    private final String defaultModel;

    public HuggingFaceAdapter(
            @Qualifier("llmWebClient") WebClient webClient,
            @Value("${gateway.llm.huggingface.api-key}") String apiKey,
            @Value("${gateway.llm.huggingface.model}") String defaultModel) {

        this.webClient    = webClient;
        this.apiKey       = apiKey;
        this.defaultModel = defaultModel;
    }

    @Override
    public String providerName() {
        return "huggingface";
    }

    @Override
    public Mono<CompletionResult> complete(CompletionRequest request) {
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model()
                : defaultModel;

        HfChatRequest hfRequest = buildHfRequest(request, model);
        long startMs = Instant.now().toEpochMilli();

        log.debug("[HF] Sending completion requestId={} model={}",
                request.requestId(), model);

        return webClient.post()
                .uri("https://api-inference.huggingface.co/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(hfRequest)
                .retrieve()
                .bodyToMono(HfChatResponse.class)
                .map(response -> {
                    long latencyMs = Instant.now().toEpochMilli() - startMs;
                    String content = String.valueOf(response.choices().get(0).message());

                    int promptTokens     = response.usage() != null
                            ? response.usage().promptTokens() : 0;
                    int completionTokens = response.usage() != null
                            ? response.usage().completionTokens() : 0;

                    log.info("[HF] Completion done requestId={} latency={}ms tokens={}/{}",
                            request.requestId(), latencyMs,
                            promptTokens, completionTokens);

                    return new CompletionResult(
                            content, model, providerName(),
                            promptTokens, completionTokens, latencyMs
                    );
                })
                .onErrorMap(WebClientResponseException.class, ex -> {
                    log.error("[HF] Provider error requestId={} status={} body={}",
                            request.requestId(), ex.getStatusCode(), ex.getResponseBodyAsString());
                    return new LlmProviderException(
                            providerName(),
                            ex.getStatusCode().value(),
                            ex.getResponseBodyAsString()
                    );
                })
                .onErrorMap(
                        e -> !(e instanceof LlmProviderException),
                        e -> new LlmProviderException(
                                providerName(),
                                "Connection failed: " + e.getMessage(),
                                e
                        )
                );
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return webClient.get()
                .uri("https://api-inference.huggingface.co/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .retrieve()
                .toBodilessEntity()
                .map(r -> r.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);
    }

    // ── HuggingFace request / response records ────────────────────────────────

    private HfChatRequest buildHfRequest(CompletionRequest request, String model) {
        var messages = new java.util.ArrayList<HfMessage>();

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new HfMessage("system", request.systemPrompt()));
        }
        messages.add(new HfMessage("user", request.userMessage()));

        return new HfChatRequest(
                model,
                List.copyOf(messages),
                request.maxTokens() > 0 ? request.maxTokens() : 512,
                request.temperature() > 0 ? request.temperature() : 0.7,
                false  // stream: always false for this pipeline
        );
    }






}