package com.aigovernance.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GlobalExceptionHandler — reactive exception handler for WebFlux.
 *
 * In WebFlux, @ControllerAdvice does not work for filter-level exceptions
 * (authentication, rate limiting). This handler catches ALL unhandled
 * exceptions across the entire reactive pipeline.
 *
 * @Order(-2) ensures this runs before Spring's default error handler.
 *
 * Every error response follows the same structure:
 * {
 *   "requestId": "...",    → for log correlation
 *   "timestamp": "...",    → ISO-8601
 *   "status": 429,         → HTTP status code
 *   "errorCode": "...",    → machine-readable error code
 *   "message": "..."       → human-readable description
 * }
 */
@Slf4j
@Order(-2)
@Component
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        String requestId = UUID.randomUUID().toString();

        HttpStatus status;
        String errorCode;
        String message;
        Map<String, Object> extras = new LinkedHashMap<>();

        if (ex instanceof RateLimitExceededException rle) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            errorCode = rle.getErrorCode();
            message = rle.getMessage();
            response.getHeaders().set("X-Retry-After", String.valueOf(rle.getRetryAfterSeconds()));
            extras.put("retryAfterSeconds", rle.getRetryAfterSeconds());

        } else if (ex instanceof GovernanceViolationException gve) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            errorCode = gve.getErrorCode();
            message = gve.getMessage();
            extras.put("violations", gve.getViolations());
            extras.put("governanceScore", gve.getGovernanceScore());

        } else if (ex instanceof LlmProviderException lpe) {
            status = HttpStatus.BAD_GATEWAY;
            errorCode = lpe.getErrorCode();
            message = lpe.getMessage();
            extras.put("provider", lpe.getProvider());

        } else if (ex instanceof AuthenticationException ae) {
            status = HttpStatus.UNAUTHORIZED;
            errorCode = ae.getErrorCode();
            message = ae.getMessage();

        } else if (ex instanceof BudgetExceededException bee) {
            status = HttpStatus.PAYMENT_REQUIRED;
            errorCode = bee.getErrorCode();
            message = bee.getMessage();
            extras.put("currentSpendUsd", bee.getCurrentSpendUsd());
            extras.put("limitUsd", bee.getLimitUsd());

        } else if (ex instanceof GatewayException ge) {
            status = ge.getStatus();
            errorCode = ge.getErrorCode();
            message = ge.getMessage();

        } else {
            // Unexpected exception — log full stack trace, hide details from client
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "INTERNAL_ERROR";
            message = "An unexpected error occurred. Reference: " + requestId;
            log.error("[{}] Unhandled exception on {} {}",
                    requestId,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    ex);
        }

        log.warn("[{}] {} {} → {} {}",
                requestId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                status.value(),
                errorCode);

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("errorCode", errorCode);
        body.put("message", message);
        body.putAll(extras);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to write error response", e);
            return response.setComplete();
        }
    }
}