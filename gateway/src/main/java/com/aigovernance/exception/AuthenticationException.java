package com.aigovernance.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown for authentication failures.
 * Results in HTTP 401 Unauthorized.
 *
 * Intentionally vague message — do not reveal why auth failed
 * (prevents enumeration attacks: "user not found" vs "wrong password").
 */
public class AuthenticationException extends GatewayException {

    public AuthenticationException() {
        super(
                HttpStatus.UNAUTHORIZED,
                "AUTH_INVALID_CREDENTIALS",
                "Authentication failed. Check your credentials."
        );
    }

    public AuthenticationException(String errorCode, String message) {
        super(HttpStatus.UNAUTHORIZED, errorCode, message);
    }
}