package com.aigovernance.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a user exceeds their rate limit bucket.
 * Results in HTTP 429 Too Many Requests.
 *
 * retryAfterSeconds: how long the client should wait before retrying.
 * Included in the response body and X-Retry-After header.
 */
@Getter
public class RateLimitExceededException extends GatewayException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String userId, long retryAfterSeconds) {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                String.format("Rate limit exceeded for user '%s'. Retry after %d seconds.",
                        userId, retryAfterSeconds)
        );
        this.retryAfterSeconds = retryAfterSeconds;
    }

}