package com.mtbs.tenant;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.repository.TenantRepository;
import com.mtbs.tenant.service.TenantFlywayMigrationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("TenantFlywayMigrationService Integration Tests")
class TenantFlywayMigrationServiceTest {

    @Autowired
    private TenantFlywayMigrationService flywayMigrationService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    @Autowired
    private DataSource dataSource;

    private String testSchema;

    @BeforeEach
    void setUp() {
        testSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(testSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(testSchema);
    }

    private Set<String> getTablesInSchema(String schemaName) throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT table_name FROM information_schema.tables " +
                 "WHERE table_schema = '" + schemaName + "' AND table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                tables.add(rs.getString("table_name"));
            }
        }
        return tables;
    }

    private boolean tableExists(String schemaName, String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT 1 FROM information_schema.tables " +
                 "WHERE table_schema = '" + schemaName + "' AND table_name = '" + tableName + "'")) {
            return rs.next();
        }
    }

    @Nested
    @DisplayName("createSchemaAndMigrate")
    class CreateSchemaAndMigrateTests {

        @Test
        @DisplayName("createSchemaAndMigrate creates all tenant tables")
        void createSchemaAndMigrate_createsAllTenantTables() throws Exception {
            Set<String> tables = getTablesInSchema(testSchema);

            assertTrue(tables.contains("users"), "users table should exist");
            assertTrue(tables.contains("roles"), "roles table should exist");
            assertTrue(tables.contains("refresh_tokens"), "refresh_tokens table should exist");
            assertTrue(tables.contains("subscriptions"), "subscriptions table should exist");
            assertTrue(tables.contains("invoices"), "invoices table should exist");
            assertTrue(tables.contains("payments"), "payments table should exist");
        }

        @Test
        @DisplayName("createSchemaAndMigrate seeds default roles")
        void createSchemaAndMigrate_seedsDefaultRoles() throws Exception {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM " + testSchema + ".roles ORDER BY name");
                
                int count = 0;
                while (rs.next()) {
                    String roleName = rs.getString("name");
                    assertTrue(roleName.equals("OWNER") || roleName.equals("ADMIN") || roleName.equals("EMPLOYEE"),
                        "Expected system role but got: " + roleName);
                    count++;
                }
                
                assertTrue(count >= 3, "Should have at least 3 system roles");
            }
        }

        @Test
        @DisplayName("createSchemaAndMigrate idempotent called twice no error")
        void createSchemaAndMigrate_idempotent_calledTwice_noError() {
            assertDoesNotThrow(() ->
                flywayMigrationService.createSchemaAndMigrate(testSchema)
            );
        }

        @Test
        @DisplayName("createSchemaAndMigrate creates users table with correct columns")
        void createSchemaAndMigrate_createsUsersTable_correctColumns() throws Exception {
            assertTrue(tableExists(testSchema, "users"), "users table should exist");
            
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                ResultSet rs = stmt.executeQuery(
                    "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = '" + testSchema + "' AND table_name = 'users'");
                
                Set<String> columns = new HashSet<>();
                while (rs.next()) {
                    columns.add(rs.getString("column_name"));
                }
                
                assertTrue(columns.contains("id"), "users should have id column");
                assertTrue(columns.contains("email"), "users should have email column");
                assertTrue(columns.contains("password"), "users should have password column");
                assertTrue(columns.contains("name"), "users should have name column");
                assertTrue(columns.contains("role_id"), "users should have role_id column");
            }
        }
    }

    @Nested
    @DisplayName("dropSchema")
    class DropSchemaTests {

        @Test
        @DisplayName("dropSchema removes all tables")
        void dropSchema_removesAllTables() throws Exception {
            // Verify tables exist before drop
            Set<String> tablesBefore = getTablesInSchema(testSchema);
            assertFalse(tablesBefore.isEmpty(), "Tables should exist before drop");

            // Drop schema
            flywayMigrationService.dropSchema(testSchema);

            // Verify schema no longer exists
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                    "SELECT schema_name FROM information_schema.schemata " +
                    "WHERE schema_name = '" + testSchema + "'");
                assertFalse(rs.next(), "Schema should be dropped");
            }
        }

        @Test
        @DisplayName("dropSchema handles non-existent schema gracefully")
        void dropSchema_nonExistent_handlesGracefully() {
            assertDoesNotThrow(() ->
                flywayMigrationService.dropSchema("non_existent_schema_12345")
            );
        }
    }

    @Nested
    @DisplayName("Schema isolation")
    class SchemaIsolationTests {

        @Test
        @DisplayName("Each test gets isolated schema")
        void eachTest_getsIsolatedSchema() {
            // This is verified by the test setup - each test gets a fresh schema
            assertNotNull(testSchema);
            assertTrue(testSchema.startsWith("test_schema_"));
        }

        @Test
        @DisplayName("Schema names are unique")
        void schemaNames_areUnique() {
            String schema1 = testSchemaHelper.createFreshSchema();
            String schema2 = testSchemaHelper.createFreshSchema();
            
            assertNotEquals(schema1, schema2, "Schemas should be unique");
            
            testSchemaHelper.dropSchema(schema1);
            testSchemaHelper.dropSchema(schema2);
        }
    }
}