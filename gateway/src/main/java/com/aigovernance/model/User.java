package com.aigovernance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * User entity — mapped to the users table via R2DBC.
 * Used for JWT authentication and per-user rate limiting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
public class User {

    @Id
    private UUID id;
    private String email;
    private String password;  // bcrypt hash
    private String apiKey;
    private String role;
    private Instant createdAt;
    private boolean isActive;
}