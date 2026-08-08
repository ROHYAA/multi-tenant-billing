package com.mtbs.support;

import com.mtbs.tenant.service.TenantFlywayMigrationService;
import com.mtbs.shared.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TestSchemaHelper {

    private static final String TEST_SCHEMA_PREFIX = "test_schema_";

    private final TenantFlywayMigrationService flywayMigrationService;
    private final DataSource dataSource;

    public String createFreshSchema() {
        String schemaName = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        log.info("Creating fresh test schema: {}", schemaName);

        flywayMigrationService.createSchemaAndMigrate(schemaName);

        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(schemaName);

        log.info("Test schema created and TenantContext initialized: {}", schemaName);
        return schemaName;
    }

    public void dropSchema(String schemaName) {
        if (schemaName == null || schemaName.isEmpty()) {
            log.warn("Cannot drop empty schema name");
            return;
        }

        log.info("Dropping test schema: {}", schemaName);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Successfully dropped schema: {}", schemaName);
        } catch (Exception e) {
            log.error("Failed to drop schema: {}", schemaName, e);
            throw new RuntimeException("Failed to drop test schema: " + schemaName, e);
        }

        TenantContext.clear();
    }

    public void clearTenantContext() {
        TenantContext.clear();
    }

    public void truncateAllTables(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return;
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("SET CONSTRAINTS ALL DEFERRED");

            var tables = stmt.executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname = '" + schemaName + "'"
            );

            while (tables.next()) {
                String tableName = tables.getString("tablename");
                if (!tableName.equals("flyway_schema_history")) {
                    stmt.execute("DELETE FROM \"" + schemaName + "\".\"" + tableName + "\"");
                }
            }

            log.debug("Truncated all tables in schema: {}", schemaName);
        } catch (Exception e) {
            log.warn("Failed to truncate tables in schema {}: {}", schemaName, e.getMessage());
        }
    }
}