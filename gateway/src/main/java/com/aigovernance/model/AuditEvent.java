// audit/AuditEvent.java
package com.aigovernance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    private UUID    userId;
    private String  requestId;
    private String  provider;
    private String  model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Double  estimatedCostUsd;
    private Integer latencyMs;
    private Boolean cacheHit;
    private Boolean governancePassed;
    private Double  governanceScore;
    private String  safetyFlags;        // stored as comma-separated string
    private Instant createdAt;
}