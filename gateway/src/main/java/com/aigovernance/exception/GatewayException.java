package com.aigovernance.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all gateway-specific errors.
 * Every exception in this application extends this class.
 * Carries: HTTP status, error code (for API clients), and message.
 *
 * Error code convention: DOMAIN_SPECIFIC_ERROR
 * e.g. AUTH_TOKEN_EXPIRED, RATE_LIMIT_EXCEEDED, GOVERNANCE_SAFETY_BLOCKED
 */
@Getter
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public GatewayException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public GatewayException(HttpStatus status, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

}