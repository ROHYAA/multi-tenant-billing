package com.mtbs.tenant.service;

import com.mtbs.shared.exception.TenantException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantFlywayMigrationService {

    private final DataSource dataSource;

    /**
     * Creates a new schema for the tenant and runs all tenant-scoped Flyway
     * migrations.
     */
    public void createSchemaAndMigrate(String schemaName) {
        log.info("Creating schema and running migrations for tenant: {}", schemaName);
        try {
            // Create schema if it doesn't exist
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            }

            // Run tenant migrations against the new schema
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/tenant")
                    .schemas(schemaName)
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();

            log.info("Successfully created schema and ran migrations for: {}", schemaName);
        } catch (Exception e) {
            log.error("Failed to create schema for tenant: {}", schemaName, e);
            throw TenantException.schemaError(schemaName, e.getMessage());
        }
    }

    /**
     * Drops a tenant schema â€” used in tests only.
     */
    public void dropSchema(String schemaName) {
        log.warn("Dropping schema: {}", schemaName);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Successfully dropped schema: {}", schemaName);
        } catch (Exception e) {
            log.error("Failed to drop schema: {}", schemaName, e);
            throw TenantException.schemaError(schemaName, e.getMessage());
        }
    }
}
