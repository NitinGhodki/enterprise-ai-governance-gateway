package com.aigovernance.service.impl;

import com.aigovernance.audit.AuditService;
import com.aigovernance.cache.SemanticCacheService;
import com.aigovernance.dto.GovernanceReport;
import com.aigovernance.dto.request.*;
import com.aigovernance.dto.response.ChatResponse;
import com.aigovernance.dto.response.CompletionResult;
import com.aigovernance.dto.response.SafetyCheckResponse;
import com.aigovernance.exception.GovernanceViolationException;
import com.aigovernance.governance.GovernanceClient;
import com.aigovernance.service.LlmAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LlmProxyService — orchestrates the complete request pipeline.
 *
 * Pipeline (all reactive, non-blocking):
 *   1. Semantic cache lookup  (Redis — ~5ms on hit)
 *   2. Input safety check     (Python governance — ~150ms)
 *   3. LLM completion         (HuggingFace/Ollama — 1000-5000ms)
 *   4. Output quality check   (Python governance — ~200ms)
 *   5. Cost estimation        (Python governance — ~10ms)
 *   6. Cache store            (Redis — ~5ms, async)
 *   7. Audit record           (PostgreSQL — ~10ms, async fire-and-forget)
 *   8. Return ChatResponse
 *
 * Reactive error handling:
 *   GovernanceViolationException → propagates to GlobalExceptionHandler → 422
 *   LlmProviderException         → propagates to GlobalExceptionHandler → 502
 *   Any other exception          → GlobalExceptionHandler → 500
 *
 * Nothing in this class blocks. Every operation is a Mono<T>.
 */
@Slf4j
@Service
public class LlmProxyService {

    private final Map<String, LlmAdapter> adapters;
    private final SemanticCacheService    cacheService;
    private final GovernanceClient        governanceClient;
    private final AuditService auditService;
    private final String                  defaultProvider;

    public LlmProxyService(
            List<LlmAdapter> adapters,
            SemanticCacheService cacheService,
            GovernanceClient governanceClient,
            AuditService auditService,
            @Value("${gateway.llm.default-provider}") String defaultProvider) {

        // Build adapter map: providerName → adapter
        // Allows O(1) lookup by name rather than iterating the list
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        LlmAdapter::providerName,
                        Function.identity()
                ));

        this.cacheService      = cacheService;
        this.governanceClient  = governanceClient;
        this.auditService      = auditService;
        this.defaultProvider   = defaultProvider;

        log.info("LlmProxyService ready. Adapters: {} Default: {}",
                this.adapters.keySet(), defaultProvider);
    }

    /**
     * Process a chat request through the full governance pipeline.
     *
     * @param request    validated ChatRequest from the controller
     * @param userId     authenticated user ID from SecurityContext
     * @return           ChatResponse with answer and governance metadata
     */
    public Mono<ChatResponse> process(ChatRequest request, String userId) {
        String requestId = UUID.randomUUID().toString();
        long   startMs   = Instant.now().toEpochMilli();
        String provider  = resolveProvider(request.provider());
        String model     = resolveModel(request, provider);

        log.info("[Proxy] START requestId={} userId={} provider={} model={}",
                requestId, userId, provider, model);

        // Step 1: Semantic cache lookup
        return cacheService.lookup(request.message())
                .flatMap(cacheEntry -> {
                    // Cache HIT — return immediately without calling LLM
                    log.info("[Proxy] Cache HIT requestId={}", requestId);
                    long latencyMs = Instant.now().toEpochMilli() - startMs;

                    GovernanceReport report = new GovernanceReport(
                            true, 1.0, List.of(), 0.0
                    );
                    ChatResponse response = buildResponse(
                            requestId, request.message(),
                            cacheEntry.answer(),
                            cacheEntry.provider(), cacheEntry.model(),
                            cacheEntry.promptTokens(), cacheEntry.completionTokens(),
                            0.0, latencyMs, true, report
                    );

                    // Audit cache hit asynchronously
                    auditService.recordAsync(
                            userId, requestId, cacheEntry.provider(),
                            cacheEntry.model(), cacheEntry.promptTokens(),
                            cacheEntry.completionTokens(), 0.0,
                            latencyMs, true, true, 1.0, List.of()
                    );

                    return Mono.just(response);
                })
                // Step 2: Cache MISS — full pipeline
                .switchIfEmpty(
                        processFullPipeline(request, userId, requestId,
                                startMs, provider, model)
                );
    }

    // Private: full pipeline on cache miss 

    private Mono<ChatResponse> processFullPipeline(
            ChatRequest request,
            String userId,
            String requestId,
            long startMs,
            String provider,
            String model) {

        log.debug("[Proxy] Cache MISS requestId={} — running full pipeline", requestId);

        LlmAdapter adapter = resolveAdapter(provider);

        // Step 2a: Input safety check 
        SafetyCheckRequest safetyReq =
                new SafetyCheckRequest(
                        requestId, userId,
                        request.message(),
                        request.systemPrompt(),
                        provider
                );

        return governanceClient.checkSafety(safetyReq)
                .flatMap(safetyResult -> {
                    // Safety BLOCKED — exception thrown inside checkSafety()
                    // and propagated here. This flatMap only runs if safe.
                    if (!safetyResult.isSafe()) {
                        return Mono.error(new GovernanceViolationException(
                                "SAFETY",
                                safetyResult.violations(),
                                0.0
                        ));
                    }

                    // Use redacted message if PII was found
                    String effectiveMessage = safetyResult.redactedMessage() != null
                            ? safetyResult.redactedMessage()
                            : request.message();

                    log.debug("[Proxy] Safety passed requestId={} piiRedacted={}",
                            requestId, safetyResult.piiDetected());

                    // Step 2b: LLM completion 
                    CompletionRequest completionReq =
                            new CompletionRequest(
                                    model,
                                    request.systemPrompt(),
                                    effectiveMessage,
                                    512,
                                    0.7,
                                    requestId
                            );

                    return adapter.complete(completionReq)
                            .flatMap(completionResult ->
                                    // ── Steps 3 & 4: Quality + cost (parallel) ─────
                                    runPostGenerationChecks(
                                            request, userId, requestId,
                                            startMs, provider, safetyResult,
                                            completionResult
                                    )
                            );
                });
    }

    /**
     * Run quality check and cost estimation in parallel (both depend on
     * completion result but are independent of each other).
     * Then store in cache and write audit record.
     */
    private Mono<ChatResponse> runPostGenerationChecks(
            ChatRequest request,
            String userId,
            String requestId,
            long startMs,
            String provider,
            SafetyCheckResponse safetyResult,
            CompletionResult completionResult) {

        // Quality check request
        QualityCheckRequest qualityReq =
                new QualityCheckRequest(
                        requestId, userId,
                        request.message(),
                        completionResult.content(),
                        List.of(),      // no RAG context in basic proxy
                        provider,
                        completionResult.model()
                );

        // Cost estimate request
        CostEstimateRequest costReq =
                new CostEstimateRequest(
                        request.message(),
                        completionResult.content(),
                        provider,
                        completionResult.model()
                );

        // Run quality and cost in parallel
        return Mono.zip(
                        governanceClient.checkQuality(qualityReq),
                        governanceClient.estimateCost(costReq)
                )
                .flatMap(tuple -> {
                    var qualityResult = tuple.getT1();
                    var costResult    = tuple.getT2();

                    long latencyMs = Instant.now().toEpochMilli() - startMs;
                    double costUsd = costResult.costUsd();

                    GovernanceReport report = governanceClient.buildReport(
                            safetyResult, qualityResult, costUsd
                    );

                    log.info("[Proxy] DONE requestId={} latency={}ms " +
                                    "quality={} cost=${} cached=false",
                            requestId, latencyMs,
                            String.format("%.4f", qualityResult.overallScore()),
                            String.format("%.6f", costUsd));

                    ChatResponse response = buildResponse(
                            requestId, request.message(),
                            completionResult.content(),
                            completionResult.provider(),
                            completionResult.model(),
                            completionResult.promptTokens(),
                            completionResult.completionTokens(),
                            costUsd, latencyMs, false, report
                    );

                    // ── Step 5: Store in cache (async, do not delay response) ─
                    cacheService.getEmbedding(request.message())
                            .flatMap(embedding ->
                                    cacheService.store(
                                            request.message(),
                                            completionResult.content(),
                                            embedding,
                                            completionResult.provider(),
                                            completionResult.model(),
                                            completionResult.promptTokens(),
                                            completionResult.completionTokens()
                                    )
                            )
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe(
                                    v  -> log.debug("[Cache] Stored requestId={}", requestId),
                                    e  -> log.warn("[Cache] Store failed requestId={}: {}",
                                            requestId, e.getMessage())
                            );

                    // Step 6: Audit record (async, fire-and-forget) 
                    auditService.recordAsync(
                            userId, requestId,
                            completionResult.provider(),
                            completionResult.model(),
                            completionResult.promptTokens(),
                            completionResult.completionTokens(),
                            costUsd, latencyMs,
                            false,
                            qualityResult.qualityPassed(),
                            qualityResult.overallScore(),
                            safetyResult.violations()
                    );

                    return Mono.just(response);
                });
    }

    // Private helpers 

    private String resolveProvider(String requested) {
        if (requested != null && adapters.containsKey(requested.toLowerCase())) {
            return requested.toLowerCase();
        }
        return defaultProvider;
    }

    private String resolveModel(ChatRequest request, String provider) {
        if (request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        return switch (provider) {
            case "ollama"      -> "mistral";
            case "huggingface" -> "mistralai/Mistral-7B-Instruct-v0.3";
            default            -> "mistralai/Mistral-7B-Instruct-v0.3";
        };
    }

    private LlmAdapter resolveAdapter(String provider) {
        LlmAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            log.warn("Unknown provider '{}', falling back to '{}'",
                    provider, defaultProvider);
            adapter = adapters.get(defaultProvider);
        }
        return adapter;
    }

    private ChatResponse buildResponse(
            String requestId, String question, String answer,
            String provider, String model,
            int promptTokens, int completionTokens,
            double costUsd, long latencyMs,
            boolean cacheHit, GovernanceReport governance) {

        return new ChatResponse(
                requestId, answer, provider, model,
                promptTokens, completionTokens,
                costUsd, latencyMs, cacheHit,
                governance, Instant.now()
        );
    }
}