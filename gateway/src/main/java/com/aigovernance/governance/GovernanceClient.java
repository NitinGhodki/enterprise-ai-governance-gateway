package com.aigovernance.governance;

import com.aigovernance.dto.GovernanceReport;
import com.aigovernance.dto.request.CostEstimateRequest;
import com.aigovernance.dto.request.EmbeddingRequest;
import com.aigovernance.dto.request.QualityCheckRequest;
import com.aigovernance.dto.request.SafetyCheckRequest;
import com.aigovernance.dto.response.CostEstimateResponse;
import com.aigovernance.dto.response.EmbeddingResponse;
import com.aigovernance.dto.response.QualityCheckResponse;
import com.aigovernance.dto.response.SafetyCheckResponse;
import com.aigovernance.exception.GatewayException;
import com.aigovernance.exception.GovernanceViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * GovernanceClient — reactive HTTP client for the Python governance service.
 *
 * All calls are non-blocking WebClient calls returning Mono<T>.
 * Timeout is configured in WebClientConfig (30 seconds for governance).
 *
 * Error handling:
 *   4xx from governance → governance violation → GovernanceViolationException
 *   5xx from governance → governance service error → GatewayException
 *   Timeout → governance service unavailable → GatewayException
 *   Network error → governance service unreachable → GatewayException
 *
 * Fallback strategy: if governance service is unreachable AND the
 * request is not a safety-critical path, allow it through with a
 * degraded governance report. This prevents the governance service
 * from becoming a single point of failure.
 * Safety paths (injection detection) NEVER have a fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceClient {

    @Qualifier("governanceWebClient")
    private final WebClient webClient;

    @Value("${gateway.governance.base-url}")
    private String governanceBaseUrl;

    /**
     * Check input safety. NEVER skipped — no fallback.
     * A governance service failure on safety check blocks the request.
     */
    public Mono<SafetyCheckResponse> checkSafety(SafetyCheckRequest request) {
        return webClient.post()
                .uri("/governance/safety")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SafetyCheckResponse.class)
                .doOnNext(response -> {
                    if (!response.isSafe()) {
                        log.warn("[Safety] BLOCKED requestId={} violations={}",
                                request.requestId(), response.violations());
                    }
                })
                .onErrorMap(WebClientResponseException.class, ex ->
                        new GovernanceViolationException(
                                "SAFETY",
                                List.of("Governance service rejected request: "
                                        + ex.getMessage()),
                                0.0
                        )
                )
                .onErrorMap(
                        e -> !(e instanceof GovernanceViolationException),
                        e -> new GatewayException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "GOVERNANCE_UNAVAILABLE",
                                "Safety governance service is unavailable. " +
                                        "Request blocked for safety."
                        )
                );
    }

    /**
     * Check output quality. Has degraded fallback.
     * If governance is unreachable, allow response through with a
     * minimal quality report flagged as unverified.
     */
    public Mono<QualityCheckResponse> checkQuality(QualityCheckRequest request) {
        return webClient.post()
                .uri("/governance/quality")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(QualityCheckResponse.class)
                .onErrorResume(e -> {
                    log.warn("[Quality] Governance unavailable for quality check " +
                                    "requestId={}. Allowing with degraded report: {}",
                            request.requestId(), e.getMessage());
                    // Degraded fallback: pass with low confidence score
                    return Mono.just(new QualityCheckResponse(
                            request.requestId(),
                            true,           // pass — do not block user
                            -1.0,           // sentinel: -1 means unverified
                            -1.0,
                            -1.0,
                            List.of("Quality check unavailable — unverified"),
                            0
                    ));
                });
    }

    /**
     * Estimate token cost. Has degraded fallback (return zero cost).
     */
    public Mono<CostEstimateResponse> estimateCost(CostEstimateRequest request) {
        return webClient.post()
                .uri("/governance/cost")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(CostEstimateResponse.class)
                .onErrorResume(e -> {
                    log.warn("[Cost] Cost estimation unavailable: {}", e.getMessage());
                    return Mono.just(new CostEstimateResponse(
                            0, 0, 0, 0.0, request.provider(), request.model()
                    ));
                });
    }

    /**
     * Get text embedding for semantic cache.
     * Has degraded fallback (empty array — cache will miss, proceed to LLM).
     */
    public Mono<float[]> getEmbedding(String text) {
        return webClient.post()
                .uri("/governance/embed")
                .bodyValue(new EmbeddingRequest(text))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .map(EmbeddingResponse::embedding)
                .onErrorResume(e -> {
                    log.warn("[Embed] Embedding service unavailable: {}", e.getMessage());
                    return Mono.just(new float[0]);  // empty → cache miss
                });
    }

    /**
     * Build a GovernanceReport from governance service responses.
     * Called after both safety and quality checks complete.
     */
    public GovernanceReport buildReport(
            SafetyCheckResponse safety,
            QualityCheckResponse quality,
            double costUsd) {

        List<String> flags = new java.util.ArrayList<>();
        if (safety.piiDetected()) flags.add("PII_DETECTED_AND_REDACTED");
        flags.addAll(quality.failureReasons());

        return new GovernanceReport(
                safety.isSafe(),
                quality.overallScore(),
                flags,
                costUsd
        );
    }
}