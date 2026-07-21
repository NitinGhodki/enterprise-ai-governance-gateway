package com.aigovernance.service.impl;

import com.aigovernance.auth.JwtService;
import com.aigovernance.auth.UserRepository;
import com.aigovernance.dto.request.LoginRequest;
import com.aigovernance.dto.request.RegisterRequest;
import com.aigovernance.dto.response.AuthResponse;
import com.aigovernance.exception.AuthenticationException;
import com.aigovernance.exception.GatewayException;
import com.aigovernance.model.User;
import com.aigovernance.service.AuthService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    public Mono<ResponseEntity<AuthResponse>> register(RegisterRequest request) {
        return userRepository.existsByEmail(request.email())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new GatewayException(
                                HttpStatus.CONFLICT,
                                "AUTH_EMAIL_ALREADY_EXISTS",
                                "An account with this email already exists."
                        ));
                    }
                    // Safe: Password encoding executes on the elastic thread pool
                    return Mono.fromCallable(() -> passwordEncoder.encode(request.password()))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                // Safe: map() un-wraps the password string from the Mono automatically
                .map(hashedPassword -> createNewUserEntity(request.email(), hashedPassword))
                // Safe: database operations consume the entity down the chain
                .flatMap(userRepository::save)
                .map(this::generateAuthResponseSuccess);
    }

    @Override
    public Mono<ResponseEntity<AuthResponse>> login(LoginRequest request) {
        return userRepository.findByEmailAndIsActiveTrue(request.email())
                .switchIfEmpty(Mono.error(new AuthenticationException()))
                .flatMap(user -> verifyPasswordAsync(request.password(), user)
                        .flatMap(matches -> validateAndBuildResponse(matches, user)));
    }

    private Mono<Boolean> verifyPasswordAsync(String rawPassword, User user) {
        // Isolates the heavy CPU-bound BCrypt check to the elastic thread pool
        return Mono.fromCallable(() -> passwordEncoder.matches(rawPassword, user.getPassword()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private Mono<ResponseEntity<AuthResponse>> validateAndBuildResponse(boolean matches, User user) {
        if (!matches) {
            return Mono.error(new AuthenticationException());
        }

        String token = jwtService.generateToken(
                user.getId().toString(),
                user.getEmail(),
                user.getRole()
        );

        log.info("User logged in successfully: id={} email={}", user.getId(), user.getEmail());

        AuthResponse response = new AuthResponse(
                token,
                user.getId().toString(),
                user.getEmail(),
                user.getRole(),
                Instant.now().plusSeconds(86400) // 24 Hours TTL
        );

        return Mono.just(ResponseEntity.ok(response));
    }

    private User createNewUserEntity(String email, String hashedPassword) {
        String apiKey = "gw-" + UUID.randomUUID().toString().replace("-", "");
        return User.builder()
                .email(email)
                .password(hashedPassword)
                .apiKey(apiKey)
                .role("USER")
                .isActive(true)
                .build();
    }

    private ResponseEntity<AuthResponse> generateAuthResponseSuccess(User savedUser) {
        String token = jwtService.generateToken(
                savedUser.getId().toString(),
                savedUser.getEmail(),
                savedUser.getRole()
        );

        log.info("User registered: id={} email={}", savedUser.getId(), savedUser.getEmail());

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new AuthResponse(
                        token,
                        savedUser.getId().toString(),
                        savedUser.getEmail(),
                        savedUser.getRole(),
                        Instant.now().plusSeconds(86400)
                )
        );
    }
}
