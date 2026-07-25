package com.aigovernance.controller;

import com.aigovernance.dto.SystemStats;
import com.aigovernance.dto.request.BudgetUpdateRequest;
import com.aigovernance.dto.response.BudgetResponse;
import com.aigovernance.dto.response.UserSummary;
import com.aigovernance.dto.response.ViolationSummary;
import com.aigovernance.model.AuditEvent;
import com.aigovernance.service.AdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * AdminController — management endpoints for ADMIN role only.
 *
 * All endpoints require ADMIN role.
 * SecurityConfig enforces /api/v1/admin/** → hasRole("ADMIN").
 * @PreAuthorize adds method-level security as defence-in-depth.
 *
 * Endpoints:
 *   GET /api/v1/admin/stats                   — system-wide statistics
 *   GET /api/v1/admin/users                   — list all users
 *   GET /api/v1/admin/users/{userId}/audit    — audit log for one user
 *   GET /api/v1/admin/users/{userId}/budget   — budget status for one user
 *   PUT /api/v1/admin/users/{userId}/budget   — update user budget limit
 *   GET /api/v1/admin/audit/recent            — recent audit events (all users)
 *   GET /api/v1/admin/audit/violations        — governance violations only
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    // ── System statistics

    /**
     * GET /api/v1/admin/stats
     * System-wide usage statistics across all users.
     *
     * curl http://localhost:8080/api/v1/admin/stats \
     *   -H "Authorization: Bearer $ADMIN_TOKEN"
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<SystemStats>> getSystemStats() {
        return adminService.getSystemMetrics()
                .map(ResponseEntity::ok);
    }
    // ── User management

    /**
     * GET /api/v1/admin/users
     * List all registered users (without passwords).
     *
     * curl http://localhost:8080/api/v1/admin/users \
     *   -H "Authorization: Bearer $ADMIN_TOKEN"
     */
    @GetMapping("/users")
    public Flux<UserSummary> listUsers() {
        return adminService.getAllUserSummaries();
    }

    // ── Audit log

    /**
     * GET /api/v1/admin/users/{userId}/audit
     * Recent audit events for a specific user (last 100).
     *
     * curl http://localhost:8080/api/v1/admin/users/{userId}/audit \
     *   -H "Authorization: Bearer $ADMIN_TOKEN"
     */
    @GetMapping("/users/{userId}/audit")
    public Flux<AuditEvent> getUserAudit(@PathVariable String userId) {
        return adminService.getLatestUserAuditLog(userId);
    }

    /**
     * GET /api/v1/admin/audit/recent
     * Most recent 50 audit events across all users.
     *
     * curl http://localhost:8080/api/v1/admin/audit/recent \
     *   -H "Authorization: Bearer $ADMIN_TOKEN"
     */
    @GetMapping("/audit/recent")
    public Flux<AuditEvent> getRecentAudit() {
        return adminService.getRecentAuditLogs();
    }

    /**
     * GET /api/v1/admin/audit/violations
     * Governance violations only — useful for security monitoring.
     *
     * curl http://localhost:8080/api/v1/admin/audit/violations \
     *   -H "Authorization: Bearer $ADMIN_TOKEN"
     */
    @GetMapping("/audit/violations")
    public Flux<ViolationSummary> getViolations() {
        return adminService.getRecentViolations();
    }

    // ── Budget management

    /**
     * GET /api/v1/admin/users/{userId}/budget
     * Current budget status for a user.
     *
     * curl http://localhost:8080/api/v1/admin/users/{userId}/budget \
     *   -H "Authorization: Bearer $ADMIN_TOKEN"
     */
    @GetMapping("/users/{userId}/budget")
    public Mono<ResponseEntity<BudgetResponse>> getUserBudget(@PathVariable String userId) {
        return adminService.getUserBudgetResponse(userId)
                .map(ResponseEntity::ok);
    }

    /**
     * PUT /api/v1/admin/users/{userId}/budget
     * Update the monthly budget limit for a user.
     *
     * curl -X PUT http://localhost:8080/api/v1/admin/users/{userId}/budget \
     *   -H "Authorization: Bearer $ADMIN_TOKEN" \
     *   -H "Content-Type: application/json" \
     *   -d '{"monthlyLimitUsd": 25.00}'
     */
    @PutMapping("/users/{userId}/budget")
    public Mono<ResponseEntity<Map<String, Object>>> updateUserBudget(
            @PathVariable String userId,
            @RequestBody BudgetUpdateRequest request) {

        return adminService.updateBudgetLimit(userId, request)
                .map(ResponseEntity::ok)
                // Maps validation failures from the service layer directly to an HTTP 400 Bad Request response payload
                .onErrorResume(IllegalArgumentException.class, ex ->
                        Mono.just(ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())))
                );
    }

}