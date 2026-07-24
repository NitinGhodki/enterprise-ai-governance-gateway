package com.aigovernance.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * RateLimitFilter — enforces per-user rate limits after authentication.
 *
 * Order @Order(1): runs after JwtAuthFilter (which runs at HTTP_BASIC order,
 * equivalent to @Order(-100)). Authentication must complete before rate limiting
 * so we have a user ID to key the bucket.
 *
 * Bypass paths: actuator and auth endpoints are excluded.
 * Rate limiting unauthenticated auth endpoints would block login attempts.
 *
 * Adds X-Rate-Limit-Remaining header to every allowed response
 * so clients can track their budget.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter implements WebFilter {

    private final RateLimitService rateLimitService;

    private static final Set<String> BYPASS_PREFIXES = Set.of(
            "/actuator",
            "/api/v1/auth"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Bypass rate limiting for public paths
        boolean bypass = BYPASS_PREFIXES.stream().anyMatch(path::startsWith);
        if (bypass) {
            return chain.filter(exchange);
        }

        // Extract authenticated user ID from security context
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName())
                .flatMap(userId ->
                        rateLimitService.checkRateLimit(userId)
                                .then(
                                        // Add remaining tokens header before proceeding
                                        rateLimitService.getRemainingTokens(userId)
                                                .doOnNext(remaining ->
                                                        exchange.getResponse()
                                                                .getHeaders()
                                                                .set("X-Rate-Limit-Remaining",
                                                                        String.valueOf(remaining))
                                                )
                                                .then(chain.filter(exchange))
                                )
                )
                // If no security context (unauthenticated request to protected path),
                // let SecurityConfig handle the 401 — do not rate limit anonymous requests
                .switchIfEmpty(chain.filter(exchange));
    }
}