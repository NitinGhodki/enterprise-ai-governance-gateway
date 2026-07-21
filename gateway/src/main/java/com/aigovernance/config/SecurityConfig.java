package com.aigovernance.config;

import com.aigovernance.auth.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

/**
 * SecurityConfig — WebFlux reactive security configuration.
 *
 * Stateless design: no session, no CSRF (JWT handles auth per-request).
 * NoOpServerSecurityContextRepository: security context not stored
 * between requests — each request authenticates independently via JWT.
 *
 * Filter order:
 *   1. JwtAuthFilter (HTTP_BASIC order) — validates JWT, sets SecurityContext
 *   2. RateLimitFilter (added in RateLimitConfig) — enforces per-user rate limit
 *   3. GovernanceFilter (added in GovernanceConfig) — safety + quality checks
 *   4. Route handler — LlmProxyController
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ReactiveUserDetailsService userDetailsService;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Disable CSRF — stateless JWT API does not need it
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // Disable form login and HTTP Basic — we use JWT
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

                // Stateless — no session storage between requests
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

                // Authorisation rules
                .authorizeExchange(exchanges -> exchanges
                        // Public endpoints
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers("/actuator/prometheus").permitAll()
                        // Admin-only endpoints
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // All other endpoints require authentication
                        .anyExchange().authenticated()
                )

                // Custom JWT filter runs before Spring Security's built-in filters
                .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.HTTP_BASIC)

                // Custom 401 and 403 responses — no redirect to login page
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, e) ->
                                Mono.fromRunnable(() -> {
                                    exchange.getResponse()
                                            .setStatusCode(HttpStatus.UNAUTHORIZED);
                                })
                        )
                        .accessDeniedHandler((exchange, e) ->
                                Mono.fromRunnable(() -> {
                                    exchange.getResponse()
                                            .setStatusCode(HttpStatus.FORBIDDEN);
                                })
                        )
                )
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 12: ~300ms per hash on modern hardware
        // Prevents brute-force attacks even with database dump
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public ReactiveAuthenticationManager authenticationManager() {
        var manager = new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder());
        return manager;
    }
}