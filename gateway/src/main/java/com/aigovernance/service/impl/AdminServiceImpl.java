package com.aigovernance.service.impl;

import com.aigovernance.audit.AuditRepository;
import com.aigovernance.auth.UserRepository;
import com.aigovernance.dto.SystemStats;
import com.aigovernance.dto.request.BudgetUpdateRequest;
import com.aigovernance.dto.response.BudgetResponse;
import com.aigovernance.dto.response.UserSummary;
import com.aigovernance.dto.response.ViolationSummary;
import com.aigovernance.model.AuditEvent;
import com.aigovernance.service.AdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminServiceImpl implements AdminService {

    private final DatabaseClient databaseClient;
    private final UserRepository userRepository;
    private final AuditRepository auditRepository;
    private final BudgetService budgetService;

    @Override
    public Mono<SystemStats> getSystemMetrics() {
        return databaseClient.sql("""
                    SELECT
                        COALESCE(COUNT(*), 0)                                AS total_requests,
                        COALESCE(COUNT(*) FILTER (WHERE cache_hit), 0)       AS cache_hits,
                        COALESCE(COUNT(*) FILTER (WHERE NOT cache_hit), 0)   AS cache_misses,
                        COALESCE(COUNT(*) FILTER (WHERE NOT governance_passed), 0) AS violations,
                        COALESCE(SUM(estimated_cost_usd), 0.0)                 AS total_cost_usd,
                        COALESCE(AVG(latency_ms), 0.0)                         AS avg_latency_ms,
                        COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP
                            (ORDER BY latency_ms), 0.0)                        AS p95_latency_ms,
                        COALESCE(COUNT(DISTINCT user_id), 0)                 AS unique_users,
                        COALESCE(COUNT(*) FILTER (
                            WHERE created_at >= NOW() - INTERVAL '24 hours'
                        ), 0)                                       AS requests_last_24h
                    FROM audit_events
                """)
                .map((row, md) -> new SystemStats(
                        row.get("total_requests", Long.class),
                        row.get("cache_hits", Long.class),
                        row.get("cache_misses", Long.class),
                        row.get("violations", Long.class),
                        row.get("total_cost_usd", Double.class),
                        row.get("avg_latency_ms", Double.class),
                        row.get("p95_latency_ms", Double.class),
                        row.get("unique_users", Long.class),
                        row.get("requests_last_24h", Long.class)
                ))
                .one()
                // Safely falls back to the static empty structure if the query returns nothing
                .defaultIfEmpty(SystemStats.empty())
                .doOnError(error -> log.error("Failed to compile or retrieve global system audit metrics", error));
    }

    @Override
    public Flux<UserSummary> getAllUserSummaries() {
        return userRepository.findAll()
                .map(user -> new UserSummary(
                        user.getId().toString(),
                        user.getEmail(),
                        user.getRole(),
                        user.isActive(),
                        user.getCreatedAt()
                ))
                .doOnError(error -> log.error("Failed to stream global user accounts directory", error));
    }

    @Override
    public Flux<AuditEvent> getLatestUserAuditLog(String userId) {
        return Flux.defer(() -> {
                    try {
                        UUID userUuid = UUID.fromString(userId);
                        return auditRepository.findByUserIdOrderByCreatedAtDesc(userUuid)
                                .take(100);
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid UUID format submitted for user audit path: {}", userId);
                        return Flux.error(new IllegalArgumentException("Provided user ID is not a valid UUID format"));
                    }
                })
                .doOnError(error -> log.error("Failed to compile audit events index stream for user: {}", userId, error));
    }

    @Override
    public Flux<AuditEvent> getRecentAuditLogs() {
        // Flux.defer ensures Instant.now() evaluates at request time, not application startup time
        return Flux.defer(() -> {
                    Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
                    return auditRepository.findByCreatedAtAfterOrderByCreatedAtDesc(since)
                            .take(50);
                })
                .doOnError(error -> log.error("Failed to compile or stream global recent system audit logs", error));
    }

    @Override
    public Flux<ViolationSummary> getRecentViolations() {
        return databaseClient.sql("""
                    SELECT
                        a.request_id,
                        a.user_id::text,
                        u.email,
                        a.safety_flags,
                        a.governance_score,
                        a.created_at
                    FROM audit_events a
                    JOIN users u ON u.id = a.user_id
                    WHERE a.governance_passed = FALSE
                      AND a.created_at >= NOW() - INTERVAL '7 days'
                    ORDER BY a.created_at DESC
                    LIMIT 100
                """)
                .map((row, md) -> new ViolationSummary(
                        row.get("request_id", String.class),
                        row.get("user_id", String.class),
                        row.get("email", String.class),
                        row.get("safety_flags", String.class),
                        row.get("governance_score", Double.class),
                        row.get("created_at", Instant.class)
                ))
                .all()
                .doOnError(error -> log.error("Failed to compile or stream security violations report", error));
    }

    @Override
    public Mono<BudgetResponse> getUserBudgetResponse(String userId) {
        return budgetService.getCurrentBudgetStatus(userId)
                .map(status -> new BudgetResponse(
                        userId,
                        status.monthlyLimitUsd(),
                        status.currentMonthUsd(),
                        status.remainingUsd(),
                        status.usagePercentage(),
                        status.budgetMonth()
                ))
                .doOnError(error -> log.error("Failed to compute or map budget statement for user: {}", userId, error));
    }

    @Override
    public Mono<Map<String, Object>> updateBudgetLimit(String userId, BudgetUpdateRequest request) {
        // Business Rule: Limits must strictly be a positive value
        if (request.monthlyLimitUsd() <= 0) {
            log.warn("Rejected budget update for user {} due to non-positive limit: {}", userId, request.monthlyLimitUsd());
            return Mono.error(new IllegalArgumentException("monthlyLimitUsd must be greater than 0"));
        }

        return budgetService.updateLimit(userId, request.monthlyLimitUsd())
                // Mono.defer ensures Instant.now() is evaluated exactly when the transaction executes successfully
                .then(Mono.defer(() -> Mono.just(Map.<String, Object>of(
                        "userId", userId,
                        "newLimitUsd", request.monthlyLimitUsd(),
                        "updatedAt", Instant.now().toString()
                ))))
                .doOnError(error -> log.error("Failed to commit updated budget caps for user: {}", userId, error));
    }
}
