package com.aigovernance.service;

import com.aigovernance.dto.request.LoginRequest;
import com.aigovernance.dto.request.RegisterRequest;
import com.aigovernance.dto.response.AuthResponse;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

public interface AuthService {

    Mono<ResponseEntity<AuthResponse>> register(RegisterRequest request);

    Mono<ResponseEntity<AuthResponse>> login(LoginRequest request);
}
