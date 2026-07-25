package com.aigovernance.service;

import com.aigovernance.dto.SystemStats;
import com.aigovernance.dto.request.BudgetUpdateRequest;
import com.aigovernance.dto.response.BudgetResponse;
import com.aigovernance.dto.response.UserSummary;
import com.aigovernance.dto.response.ViolationSummary;
import com.aigovernance.model.AuditEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface AdminService {

    /**
     * Fetches globally aggregated system metrics and audit performance data.
     * @return Mono emitting the populated SystemStats DTO
     */
    Mono<SystemStats> getSystemMetrics();

    /**
     * Retrieves all system users transformed into a secure summary DTO view.
     * @return Flux emitting active and inactive UserSummary entries
     */
    Flux<UserSummary> getAllUserSummaries();

    /**
     * Retrieves the latest 100 audit events for a specific user.
     * @param userId The unique identifier string of the target user
     * @return Flux emitting up to 100 historical AuditEvent objects
     */
    Flux<AuditEvent> getLatestUserAuditLog(String userId);

    /**
     * Retrieves the 50 most recent audit events generated across the system within the last 24 hours.
     * @return Flux emitting up to 50 recent AuditEvent entries
     */
    Flux<AuditEvent> getRecentAuditLogs();

    /**
     * Retrieves a detailed summary of security and governance violations from the last 7 days.
     * @return Flux emitting up to 100 ViolationSummary entries
     */
    Flux<ViolationSummary> getRecentViolations();

    /**
     * Retrieves and maps the current budget status for a user into a secure API response DTO.
     * @param userId The unique identifier string of the target user
     * @return Mono emitting the mapped BudgetResponse model
     */
    Mono<BudgetResponse> getUserBudgetResponse(String userId);

    /**
     * Validates and updates a user's monthly spending limit, returning a structured summary.
     * @param userId The unique identifier string of the target user
     * @param request The object containing the new limit configurations
     * @return Mono emitting a success payload map, or an error payload if validation fails
     */
    Mono<Map<String, Object>> updateBudgetLimit(String userId, BudgetUpdateRequest request);

}
