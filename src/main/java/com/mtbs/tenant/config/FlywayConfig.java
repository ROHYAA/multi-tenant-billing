package com.mtbs.tenant.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Manages Flyway migrations for public and tenant schemas separately.
 * Spring Boot's auto-configured Flyway is DISABLED
 * (spring.flyway.enabled=false)
 * to prevent it from scanning both public/ and tenant/ directories
 * simultaneously.
 * Tenant-schema migrations are handled by TenantFlywayMigrationService.
 */
@Configuration
@Slf4j
public class FlywayConfig {

    /**
     * Runs public-schema migrations on startup using only the public migration
     * directory.
     */
    @Bean
    public Flyway publicFlyway(DataSource dataSource) {
        log.info("Running public-schema Flyway migrations...");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/public")
                .schemas("public")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        log.info("Public-schema migrations complete.");
        return flyway;
    }
}
