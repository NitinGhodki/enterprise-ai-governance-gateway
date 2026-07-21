package com.aigovernance.auth;

import com.aigovernance.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * ReactiveUserDetailsServiceImpl — loads UserDetails from PostgreSQL reactively.
 *
 * Called by ReactiveAuthenticationManager during login.
 * NOT called on every request — JwtAuthFilter handles per-request auth
 * by reading claims from the validated token instead of hitting the database.
 *
 * This prevents a database call on every authenticated request.
 * The tradeoff: user role changes do not take effect until token expiry.
 * For this system: acceptable. For banking systems: use shorter token TTL
 * (15 minutes) or maintain a token revocation list in Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactiveUserDetailsServiceImpl implements ReactiveUserDetailsService {

    private final UserRepository userRepository;

    @Override
    public Mono<UserDetails> findByUsername(String email) {
        return userRepository.findByEmailAndIsActiveTrue(email)
                .switchIfEmpty(Mono.error(new AuthenticationException()))
                .map(user -> User.withUsername(user.getEmail())
                        .password(user.getPassword())
                        .authorities(List.of(
                                new SimpleGrantedAuthority("ROLE_" + user.getRole())
                        ))
                        .accountExpired(false)
                        .accountLocked(!user.isActive())
                        .credentialsExpired(false)
                        .disabled(!user.isActive())
                        .build()
                )
                .doOnError(e -> log.debug(
                        "UserDetails lookup failed for email={}: {}",
                        email, e.getMessage()
                ));
    }
}