package com.aigovernance.audit;

import com.aigovernance.model.AuditEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuditRepository extends ReactiveCrudRepository<AuditEvent, UUID> {

    Flux<AuditEvent> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Flux<AuditEvent> findByCreatedAtAfterOrderByCreatedAtDesc(Instant after);
}