package com.aigovernance.controller;

import com.aigovernance.dto.request.ChatRequest;
import com.aigovernance.dto.response.ChatResponse;
import com.aigovernance.dto.response.StatusResponse;
import com.aigovernance.service.impl.LlmProxyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * LlmProxyController — REST entry point for LLM completion requests.
 *
 * POST /api/v1/chat — the primary endpoint.
 *
 * Security context extraction:
 *   Authentication.getName() returns the userId set by JwtAuthFilter.
 *   This is the subject from the JWT — the user's UUID.
 *   Used for: rate limiting, audit logging, cost tracking.
 *
 * Validation: @Valid triggers @NotBlank and @Size constraints
 * defined on ChatRequest fields. Validation failures return
 * HTTP 400 with field-level error details automatically.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LlmProxyController {

    private final LlmProxyService proxyService;

    /**
     * POST /api/v1/chat
     * The primary LLM proxy endpoint with full governance pipeline.
     *
     * curl -X POST http://localhost:8080/api/v1/chat \
     *   -H "Authorization: Bearer $TOKEN" \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "message": "What is the Professional plan price?",
     *     "provider": "huggingface"
     *   }'
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<ChatResponse>> chat(
            @Valid @RequestBody ChatRequest request) {

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .flatMap(userId -> proxyService.process(request, userId))
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/v1/status
     * Public status endpoint — shows gateway capabilities.
     * Authenticated but not rate-limited.
     *
     * curl http://localhost:8080/api/v1/status \
     *   -H "Authorization: Bearer $TOKEN"
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<StatusResponse>> status() {
        return Mono.just(ResponseEntity.ok(new StatusResponse(
                "Enterprise AI Governance Gateway",
                "1.0.0",
                "operational",
                java.util.List.of("huggingface", "ollama"),
                java.util.Map.of(
                        "safety_check",    "Python Presidio + pattern matching",
                        "quality_check",   "Semantic faithfulness scoring",
                        "semantic_cache",  "Redis + cosine similarity",
                        "rate_limiting",   "Bucket4j token bucket per user",
                        "audit_log",       "PostgreSQL R2DBC async"
                )
        )));
    }


}