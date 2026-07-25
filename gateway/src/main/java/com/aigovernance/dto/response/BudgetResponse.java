package com.aigovernance.dto.response;

public record BudgetResponse(
        String userId,
        double monthlyLimitUsd,
        double currentMonthUsd,
        double remainingUsd,
        double usagePercentage,
        String budgetMonth
) {}