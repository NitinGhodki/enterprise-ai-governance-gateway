package com.aigovernance.service.impl;

import com.aigovernance.exception.BudgetExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;


/**
 * BudgetService — per-user monthly spend enforcement.
 *
 * Budget lifecycle:
 *   1. PRE-CHECK  (before LLM call): estimate input-only cost,
 *      reject if currentSpend + estimate > monthlyLimit.
 *   2. POST-CHECK (after LLM call):  deduct actual cost from budget.
 *      Uses atomic SQL UPDATE to prevent race conditions.
 *
 * Storage: user_budgets table in PostgreSQL.
 * No Redis caching of budget data — budget figures must be
 * exactly accurate. Eventual consistency from Redis cache
 * could allow a user to exceed their limit by one concurrent request.
 *
 * Atomic deduction pattern:
 *   UPDATE user_budgets
 *   SET current_month_usd = current_month_usd + :cost,
 *       updated_at = NOW()
 *   WHERE user_id = :userId
 *     AND budget_month = DATE_TRUNC('month', NOW())
 *   RETURNING current_month_usd
 *
 * This single SQL statement reads + increments + returns atomically.
 * No two concurrent updates can produce the same result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final DatabaseClient databaseClient;

    /**
     * Check if the user has budget remaining for an estimated cost.
     * Called BEFORE the LLM call with input-token cost only.
     *
     * Returns Mono.empty() if budget allows.
     * Returns Mono.error(BudgetExceededException) if budget exceeded.
     */
    public Mono<Void> checkBudget(String userId, double estimatedCostUsd) {
        return getCurrentBudgetStatus(userId)
                .flatMap(status -> {
                    double projectedSpend = status.currentMonthUsd()
                            + estimatedCostUsd;

                    if (projectedSpend > status.monthlyLimitUsd()) {
                        log.warn("[Budget] BLOCKED userId={} " +
                                        "current=${} estimated=${} limit=${}",
                                userId,
                                String.format("%.6f", status.currentMonthUsd()),
                                String.format("%.6f", estimatedCostUsd),
                                String.format("%.2f", status.monthlyLimitUsd()));

                        return Mono.error(new BudgetExceededException(
                                userId,
                                status.currentMonthUsd(),
                                status.monthlyLimitUsd()
                        ));
                    }

                    log.debug("[Budget] OK userId={} projected=${}",
                            userId,
                            String.format("%.6f", projectedSpend));
                    return Mono.<Void>empty();
                })
                .onErrorResume(BudgetExceededException.class, Mono::error)
                .onErrorResume(e -> {
                    // If budget table lookup fails: allow request through.
                    // Budget enforcement must not become a system availability issue.
                    // Log the failure — alert on this in Grafana.
                    log.error("[Budget] Budget check failed for userId={}: {}. " +
                                    "Allowing request through.",
                            userId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Deduct actual cost from user's monthly budget.
     * Called AFTER LLM response with actual token count.
     * Fire-and-forget — never delays client response.
     */
    public void deductAsync(String userId, double actualCostUsd) {
        deductBudget(userId, actualCostUsd)
                .subscribe(
                        newTotal -> log.debug(
                                "[Budget] Deducted ${} from userId={}. " +
                                        "New total: ${}",
                                String.format("%.6f", actualCostUsd),
                                userId,
                                String.format("%.6f", newTotal)),
                        error -> log.error(
                                "[Budget] Deduction failed userId={}: {}",
                                userId, error.getMessage())
                );
    }

    /**
     * Get current budget status for a user.
     * Creates a default budget entry if none exists (new user).
     */
    public Mono<BudgetStatus> getCurrentBudgetStatus(String userId) {
        String currentMonth = LocalDate.now()
                .with(TemporalAdjusters.firstDayOfMonth())
                .toString();

        return databaseClient.sql("""
                    INSERT INTO user_budgets
                        (user_id, monthly_limit_usd, current_month_usd, budget_month)
                    VALUES
                        (:userId::uuid, 10.00, 0.00, :budgetMonth::date)
                    ON CONFLICT (user_id) DO UPDATE
                        SET budget_month = CASE
                                WHEN user_budgets.budget_month
                                     < DATE_TRUNC('month', NOW())::date
                                THEN :budgetMonth::date
                                ELSE user_budgets.budget_month
                            END,
                            current_month_usd = CASE
                                WHEN user_budgets.budget_month
                                     < DATE_TRUNC('month', NOW())::date
                                THEN 0.00
                                ELSE user_budgets.current_month_usd
                            END
                    RETURNING
                        monthly_limit_usd,
                        current_month_usd,
                        budget_month
                """)
                .bind("userId", userId)
                .bind("budgetMonth", currentMonth)
                .map((row, metadata) -> new BudgetStatus(
                        row.get("monthly_limit_usd", Double.class),
                        row.get("current_month_usd", Double.class),
                        row.get("budget_month", String.class)
                ))
                .one();
    }

    /**
     * Update the monthly limit for a user (admin operation).
     */
    public Mono<Void> updateLimit(String userId, double newLimitUsd) {
        return databaseClient.sql("""
                    INSERT INTO user_budgets (user_id, monthly_limit_usd)
                    VALUES (:userId::uuid, :limit)
                    ON CONFLICT (user_id)
                    DO UPDATE SET monthly_limit_usd = :limit,
                                  updated_at = NOW()
                """)
                .bind("userId", userId)
                .bind("limit", newLimitUsd)
                .then();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Mono<Double> deductBudget(String userId, double cost) {
        return databaseClient.sql("""
                    UPDATE user_budgets
                    SET current_month_usd = current_month_usd + :cost,
                        updated_at        = NOW()
                    WHERE user_id    = :userId::uuid
                      AND budget_month = DATE_TRUNC('month', NOW())::date
                    RETURNING current_month_usd
                """)
                .bind("userId", userId)
                .bind("cost", cost)
                .map((row, md) -> row.get("current_month_usd", Double.class))
                .one()
                .switchIfEmpty(Mono.just(0.0));
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record BudgetStatus(
            double monthlyLimitUsd,
            double currentMonthUsd,
            String budgetMonth
    ) {
        public double remainingUsd() {
            return Math.max(0.0, monthlyLimitUsd - currentMonthUsd);
        }

        public double usagePercentage() {
            if (monthlyLimitUsd <= 0) return 100.0;
            return (currentMonthUsd / monthlyLimitUsd) * 100.0;
        }
    }
}