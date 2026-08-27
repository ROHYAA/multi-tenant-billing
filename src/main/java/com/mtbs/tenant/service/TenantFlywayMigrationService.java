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
     * Creates a schema for the tenant (if it doesn't already exist) and runs
     * all tenant-scoped Flyway migrations against it.
     *
     * Called both at fresh signup (schema doesn't exist yet) AND, since
     * TenantMigrationRunner was added, on every app startup for every
     * EXISTING shop — this second use case is new territory: it's the first
     * code path that ever re-validates an already-migrated schema's full
     * history against the current migration files. A tenant-schema
     * migration whose content drifted even slightly after some shop's
     * schema had already applied it (a wording/formatting fix to an old
     * migration file, for instance — this migration set has been edited
     * across many sessions) fails Flyway's checksum validation and aborts
     * migrate() for that shop entirely, silently leaving it missing every
     * newer column/table added since. flyway.repair() is Flyway's own tool
     * for exactly this: it recalculates stored checksums to match the
     * current file content and clears any dangling failed-migration
     * record, and is safe/idempotent to run unconditionally before every
     * migrate() call — a no-op when there's nothing to repair.
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
            flyway.repair();
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
