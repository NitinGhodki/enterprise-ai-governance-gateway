package com.aigovernance.auth;

import com.aigovernance.exception.AuthenticationException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JwtAuthFilter — validates JWT on every incoming request.
 *
 * Processing pipeline (all reactive, no blocking):
 *   1. Extract Authorization header
 *   2. Parse "Bearer <token>" format
 *   3. Validate JWT signature and expiry (CPU-bound, no I/O)
 *   4. Build Spring Security Authentication object from claims
 *   5. Store in ReactiveSecurityContextHolder
 *   6. Pass request to next filter
 *
 * Public paths (register, login, health) bypass token validation
 * because SecurityConfig.authorizeExchange() permits them before
 * this filter's authentication is checked.
 *
 * Critically: this filter sets the security context but does NOT
 * return an error if no token is present — that is SecurityConfig's
 * job via .anyExchange().authenticated(). This separation of concerns
 * means the filter handles token parsing, not access control.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtService jwtService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        // No Authorization header — pass through, SecurityConfig handles access control
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        if (token.isBlank()) {
            return chain.filter(exchange);
        }

        try {
            // CPU-bound — safe on event loop
            Claims claims = jwtService.validateAndExtractClaims(token);
            String userId = jwtService.extractUserId(claims);
            String role = jwtService.extractRole(claims);
            String email = jwtService.extractEmail(claims);

            log.debug("JWT validated for user={} role={}", userId, role);

            // Build authentication with ROLE_ prefix (Spring Security convention)
            var authentication = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );

            // Store email in details for downstream use
            authentication.setDetails(email);

            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder
                            .withAuthentication(authentication));

        } catch (AuthenticationException e) {
            // Token present but invalid — let GlobalExceptionHandler respond
            return Mono.error(e);
        }
    }
}