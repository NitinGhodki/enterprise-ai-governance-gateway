package com.aigovernance.controller;

import com.aigovernance.dto.request.LoginRequest;
import com.aigovernance.dto.request.RegisterRequest;
import com.aigovernance.dto.response.AuthResponse;
import com.aigovernance.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;


/**
 * AuthController — registration and login endpoints.
 *
 * POST /api/v1/auth/register — create a new user account
 * POST /api/v1/auth/login    — authenticate and receive JWT
 *
 * Both are public (no JWT required) — configured in SecurityConfig.
 *
 * All operations are reactive — no blocking database calls.
 * Password hashing (.encode()) is CPU-bound but fast enough
 * at BCrypt strength 12 (~300ms) to run on a boundedElastic
 * scheduler without blocking the event loop.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ── POST /api/v1/auth/register ────────────────────────────────────────────

    /**
     * Register a new user account.
     *
     * curl -X POST http://localhost:8080/api/v1/auth/register \
     *   -H "Content-Type: application/json" \
     *   -d '{"email": "nitin@example.com", "password": "securepass123"}'
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResponseEntity<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    // ── POST /api/v1/auth/login ───────────────────────────────────────────────

    /**
     * Login with email and password, receive JWT.
     *
     * curl -X POST http://localhost:8080/api/v1/auth/login \
     *   -H "Content-Type: application/json" \
     *   -d '{"email": "nitin@example.com", "password": "securepass123"}'
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        return authService.login(request);

    }
}