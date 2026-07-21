package com.aigovernance.auth;

import com.aigovernance.model.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * UserRepository — reactive R2DBC repository for the users table.
 *
 * All methods return Mono<T> — non-blocking, never holds a thread.
 * Spring Data R2DBC generates SQL from method names automatically.
 *
 * findByApiKey: used for API key authentication (alternative to JWT).
 * findByEmailAndIsActiveTrue: used during login — only find active users.
 */
@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    Mono<User> findByEmail(String email);

    Mono<User> findByEmailAndIsActiveTrue(String email);

    Mono<User> findByApiKey(String apiKey);

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE email = :email)")
    Mono<Boolean> existsByEmail(String email);
}