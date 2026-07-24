package com.aigovernance.audit;

import com.aigovernance.model.AuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AuditService — records every LLM request to PostgreSQL.
 *
 * All writes are fire-and-forget: recordAsync() subscribes on
 * boundedElastic and does not delay the response to the client.
 * If the audit write fails, it is logged but never propagated —
 * a failed audit write must never fail the client's LLM request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository auditRepository;

    /**
     * Fire-and-forget audit record.
     * Subscribes asynchronously — caller continues immediately.
     */
    public void recordAsync(
            String       userId,
            String       requestId,
            String       provider,
            String       model,
            int          promptTokens,
            int          completionTokens,
            double       costUsd,
            long         latencyMs,
            boolean      cacheHit,
            boolean      governancePassed,
            double       governanceScore,
            List<String> safetyFlags) {

        buildEvent(userId, requestId, provider, model,
                promptTokens, completionTokens, costUsd,
                latencyMs, cacheHit, governancePassed,
                governanceScore, safetyFlags)
                .flatMap(auditRepository::save)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        saved -> log.debug("[Audit] Recorded requestId={}",
                                requestId),
                        error -> log.error("[Audit] Failed to record requestId={}: {}",
                                requestId, error.getMessage())
                );
    }

    private Mono<AuditEvent> buildEvent(
            String userId, String requestId, String provider, String model,
            int promptTokens, int completionTokens, double costUsd,
            long latencyMs, boolean cacheHit, boolean governancePassed,
            double governanceScore, List<String> safetyFlags) {

        return Mono.fromCallable(() -> AuditEvent.builder()
                .userId(UUID.fromString(userId))
                .requestId(requestId)
                .provider(provider)
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .estimatedCostUsd(costUsd)
                .latencyMs((int) latencyMs)
                .cacheHit(cacheHit)
                .governancePassed(governancePassed)
                .governanceScore(governanceScore)
                .safetyFlags(safetyFlags.isEmpty()
                        ? null
                        : String.join(",", safetyFlags))
                .createdAt(Instant.now())
                .build()
        );
    }
}