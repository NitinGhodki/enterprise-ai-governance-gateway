package com.aigovernance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enterprise AI Governance Gateway — reactive Spring Boot application.
 *
 * Architecture: Spring WebFlux (reactive/non-blocking) throughout.
 * Every I/O operation returns Mono<T> or Flux<T> — never blocks a thread.
 *
 * Memory profile (Railway deployment, 1GB total budget):
 *   JVM heap: ~300MB (-XX:MaxRAMPercentage=35)
 *   Netty event loop threads: minimal (WebFlux default: 2×CPU cores)
 *   No servlet container: Netty replaces Tomcat, saves ~50MB
 *
 * Excluded: DataSourceAutoConfiguration because Flyway uses its own
 * JDBC datasource configured manually in FlywayConfig.
 * R2DBC handles all runtime database operations reactively.
 */
@Slf4j
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GatewayApplication.class);
        app.run(args);
        log.info("""
                
                ╔══════════════════════════════════════════════╗
                ║  Enterprise AI Governance Gateway v1.0.0     ║
                ║  Runtime: Spring WebFlux (reactive/Netty)    ║
                ║  Health: /actuator/health                    ║
                ║  Metrics: /actuator/prometheus               ║
                ║  API: /api/v1/                               ║
                ╚══════════════════════════════════════════════╝
                """);
    }
}