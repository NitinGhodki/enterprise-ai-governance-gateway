package com.aigovernance.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Thrown when the Python governance service blocks a request.
 * Results in HTTP 422 Unprocessable Entity.
 *
 * violationType: "SAFETY" (input blocked) or "QUALITY" (output blocked)
 * violations: specific rules that were triggered
 * governanceScore: numeric score from the governance service (0.0–1.0)
 */
@Getter
public class GovernanceViolationException extends GatewayException {

    private final String violationType;
    private final List<String> violations;
    private final double governanceScore;

    public GovernanceViolationException(String violationType, List<String> violations, double governanceScore) {

        super(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "GOVERNANCE_" + violationType + "_VIOLATION",
                String.format("Request blocked by governance service. Type: %s. Score: %.4f",
                        violationType, governanceScore)
        );
        this.violationType = violationType;
        this.violations = List.copyOf(violations);
        this.governanceScore = governanceScore;
    }

}