package com.aigovernance.dto;

import java.util.List;

/**
 * Governance check results included in every ChatResponse.
 * Gives API consumers visibility into what the governance service evaluated.
 *
 * safetyPassed: input passed all safety checks
 * qualityScore: 0.0–1.0 faithfulness/relevancy score of the response
 * flaggedRules: safety rules that were evaluated (not triggered — all pass)
 * costUsd: cost estimated by the Python cost service
 */
public record GovernanceReport(
        boolean safetyPassed,
        double qualityScore,
        List<String> flaggedRules,
        double costUsd
) {}