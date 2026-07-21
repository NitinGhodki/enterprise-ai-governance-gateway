package com.aigovernance.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a user has exceeded their monthly cost budget.
 * Results in HTTP 402 Payment Required.
 *
 * currentSpendUsd: how much the user has spent this month
 * limitUsd: their monthly limit
 */
@Getter
public class BudgetExceededException extends GatewayException {

    private final double currentSpendUsd;
    private final double limitUsd;

    public BudgetExceededException(String userId, double currentSpendUsd, double limitUsd) {
        super(
                HttpStatus.PAYMENT_REQUIRED,
                "BUDGET_EXCEEDED",
                String.format("Monthly budget exceeded for user '%s'. Spent: $%.4f, Limit: $%.2f",
                        userId, currentSpendUsd, limitUsd)
        );
        this.currentSpendUsd = currentSpendUsd;
        this.limitUsd = limitUsd;
    }

}