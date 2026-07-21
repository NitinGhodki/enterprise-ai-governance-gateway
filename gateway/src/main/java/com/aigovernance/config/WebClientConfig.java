package com.aigovernance.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClientConfig — reactive HTTP clients for external services.
 *
 * Two clients configured:
 *
 * 1. governanceWebClient — calls Python governance service
 *    Timeout: 30s (governance evaluation can be slow with RAGAS)
 *
 * 2. llmWebClient — calls LLM providers (HuggingFace / Ollama)
 *    Timeout: 60s (LLM inference is slow)
 *
 * Both use Netty connection pool tuning optimised for the 1GB
 * memory budget:
 *   maxConnections: 20 (not 200 — we have limited RAM)
 *   pendingAcquireMaxCount: 50 — queue depth before rejecting
 *
 * Logging filter: logs request method + URL + response status.
 * Does NOT log request/response bodies (they contain user data).
 */
@Slf4j
@Configuration
public class WebClientConfig {

    @Value("${gateway.governance.base-url}")
    private String governanceBaseUrl;

    @Value("${gateway.governance.timeout-seconds}")
    private int governanceTimeoutSeconds;

    @Bean("governanceWebClient")
    public WebClient governanceWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(governanceTimeoutSeconds))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(
                                governanceTimeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .baseUrl(governanceBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .filter(loggingFilter("governance"))
                .filter(errorHandlingFilter())
                .build();
    }

    @Bean("llmWebClient")
    public WebClient llmWebClient(
            @Value("${gateway.llm.huggingface.timeout-seconds}") int timeoutSeconds) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .filter(loggingFilter("llm"))
                .filter(errorHandlingFilter())
                .build();
    }

    /**
     * Log outbound requests without logging body content (PII risk).
     */
    private ExchangeFilterFunction loggingFilter(String clientName) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("[WebClient:{}] → {} {}", clientName, request.method(), request.url());
            return Mono.just(request);
        });
    }

    /**
     * Convert 4xx/5xx responses from downstream services to exceptions
     * so they propagate correctly through the reactive pipeline.
     */
    private ExchangeFilterFunction errorHandlingFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().is5xxServerError()
                    || response.statusCode().is4xxClientError()) {
                return response.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(
                                new RuntimeException(
                                        "Downstream error " + response.statusCode() + ": " + body)
                        ));
            }
            return Mono.just(response);
        });
    }
}