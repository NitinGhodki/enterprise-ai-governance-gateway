package com.aigovernance.auth;

import com.aigovernance.exception.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * JwtService — generates and validates JWT tokens.
 *
 * Algorithm: HS256 (HMAC-SHA256) — symmetric signing.
 * In production with multiple services: use RS256 (asymmetric)
 * so services can verify without the signing secret.
 * For this single-gateway architecture: HS256 is sufficient.
 *
 * Token payload:
 *   sub: user ID (UUID string)
 *   email: user email
 *   role: USER | ADMIN
 *   jti: unique token ID (for future revocation support)
 *   iat: issued at
 *   exp: expiry
 *
 * Token validation is CPU-bound (HMAC verification) — no I/O.
 * Safe to run on Netty event loop thread without .subscribeOn().
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expiryHours;

    public JwtService(
            @Value("${gateway.jwt.secret}") String secret,
            @Value("${gateway.jwt.expiry-hours}") long expiryHours) {

        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 characters. " +
                            "Set gateway.jwt.secret in application.yml or JWT_SECRET env var."
            );
        }

        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryHours = expiryHours;
        log.info("JwtService initialised. Token expiry: {}h", expiryHours);
    }

    /**
     * Generate a signed JWT for an authenticated user.
     * Called by AuthController after successful login or registration.
     */
    public String generateToken(String userId, String email, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expiryHours, ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .id(UUID.randomUUID().toString())   // jti — unique per token
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validate a JWT and extract its claims.
     * Throws AuthenticationException on any validation failure.
     *
     * Called from JwtAuthFilter — must not perform any I/O.
     */
    public Claims validateAndExtractClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: jti={}", extractJtiSafely(e.getClaims()));
            throw new AuthenticationException(
                    "AUTH_TOKEN_EXPIRED",
                    "JWT token has expired. Please log in again."
            );
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            throw new AuthenticationException(
                    "AUTH_TOKEN_INVALID",
                    "JWT token is invalid."
            );
        }
    }

    /**
     * Extract user ID from a validated claims object.
     */
    public String extractUserId(Claims claims) {
        return claims.getSubject();
    }

    /**
     * Extract user role from a validated claims object.
     */
    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }

    /**
     * Extract email from a validated claims object.
     */
    public String extractEmail(Claims claims) {
        return claims.get("email", String.class);
    }

    private String extractJtiSafely(Claims claims) {
        try {
            return claims != null ? claims.getId() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}