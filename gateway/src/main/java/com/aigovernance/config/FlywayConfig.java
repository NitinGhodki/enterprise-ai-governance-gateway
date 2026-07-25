package com.aigovernance.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(Environment env) {
        // Read your explicit JDBC configuration parameters straight from your application.yml
        return Flyway.configure()
                .dataSource(
                        env.getRequiredProperty("spring.datasource.url"),
                        env.getRequiredProperty("spring.datasource.username"),
                        env.getRequiredProperty("spring.datasource.password")
                )
                // Maps cleanly to your db/migration folder mapping
                .locations(env.getProperty("spring.flyway.locations", "classpath:db/migration"))
                .baselineOnMigrate(Boolean.parseBoolean(env.getProperty("spring.flyway.baseline-on-migrate", "true")))
                .load();
    }
}
