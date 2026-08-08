---
version: 1.0
date: 2026-04-18
author: Manoj Pandi
status: Production Ready
tags:
  - tenant
  - provisioning
  - flyway
  - schema
  - multitenancy
  - signup
related_documents:
  - ./multi-tenancy-strategy.md
  - ./tenant-context-lifecycle.md
  - ../01-architecture/system-design.md
---

# Schema Provisioning

## Executive Summary

Schema provisioning is the process of (1) creating a new PostgreSQL schema for a tenant and (2) running all tenant-scoped Flyway migrations to bootstrap database tables, constraints, and indices. This is **critical for tenant onboarding** — without it, new tenants cannot access the application. Provisioning must be idempotent (safe to retry), isolated between concurrent tenants, and rollback-safe on failure.

The system uses a dedicated `TenantFlywayMigrationService` that orchestrates schema creation via raw SQL (`CREATE SCHEMA IF NOT EXISTS`) and Flyway's migration engine to populate tenant tables. Provisioning occurs **immediately after** a Tenant record is created in the public schema, before authentication/authorization setup.


## Context & Problem

### Why Schema Provisioning is Complex

In a schema-per-tenant multitenancy model, each tenant's data lives in a separate PostgreSQL schema. The application cannot serve requests for a tenant without the target schema existing and being initialized. This creates a **bootstrap paradox**: the schema must exist before it can be populated, yet the initial population requires schema-specific operations.

**Multi-faceted complexity:**

1. **Idempotency** — If `CREATE SCHEMA` is called twice for the same tenant, the second call must not fail. PostgreSQL's `IF NOT EXISTS` clause handles this, but Flyway's migration history must also be idempotent; `baselineOnMigrate=true` ensures Flyway skips migrations if history already exists.

2. **Isolation** — Concurrent tenant signups must not interfere. Each provisioning call gets its own Flyway instance scoped to a specific schema; PostgreSQL handles schema isolation at the SQL layer.

3. **Failure Recovery** — If provisioning fails mid-stream (e.g., Flyway migration fails, network timeout), the schema may be left in an inconsistent state. The system marks the Tenant as `ONBOARDING_ABANDONED` and must allow retry or manual intervention.

4. **Transaction Boundaries** — Schema creation (DDL) cannot be rolled back in PostgreSQL within a transaction; it commits immediately. Tenant signup must separate public schema writes (Tenant row, onboarding record — transactional and rollbackable) from tenant schema creation (DDL, immediate commit).


### Alternatives Considered & Trade-offs

| Approach | Pros | Cons |
|----------|------|------|
| **Schema per tenant (chosen)** | Hard data isolation; easy regulatory compliance; schema-level ACLs; shared infrastructure costs | Complex provisioning; Flyway complexity; Cross-schema queries require explicit design |
| Row-level security (RLS) | Single schema; simpler provisioning; simpler migrations | Weaker isolation (bugs leak data); harder compliance; tenant data co-mingled |
| Separate database per tenant | Hardest isolation; simplest provisioning per DB | Extreme operational overhead; resource explosion; backup/scaling nightmare |

**Chosen approach rationale:** Schema-per-tenant scales to hundreds of tenants on a single database while maintaining hard isolation and regulatory compliance (Indian data residency). Provisioning complexity is front-loaded; operations scale linearly by tenant count.


### Dependency Graph

```
SignupService.signup(SignupRequest)
  ├─ Validate email uniqueness (public schema)
  ├─ Create Tenant row (public schema, @Transactional)
  ├─ TenantFlywayMigrationService.createSchemaAndMigrate(schemaName)
  │   ├─ CREATE SCHEMA IF NOT EXISTS "s_{tenantId}"
  │   ├─ Flyway.configure().schemas(schemaName).baselineOnMigrate(true)
  │   └─ Flyway.migrate() [runs V1-V20 migrations]
  ├─ TenantContext.setTenantId(tenantId)
  ├─ TenantContext.setCurrentSchema(schemaName)
  ├─ TenantAuthService.createOwnerUserForSignup(request, tenant)
  │   └─ Create User + Role + RefreshToken in tenant schema
  ├─ SaveOnboardingRecord(tenantId)  (public schema, @Transactional)
  └─ FireWelcomeNotification()
```


## Design & Implementation

### Architecture: TenantFlywayMigrationService

The `TenantFlywayMigrationService` is a Spring-managed service that wraps Flyway and PostgreSQL DDL operations. It maintains zero state; each call is independent and can be retried safely.

**Key design constraints:**

1. **Flyway configuration per tenant** — Each call to `createSchemaAndMigrate()` instantiates a new Flyway bean scoped to the target schema. This avoids shared migration state and allows concurrent provisioning.

2. **Baseline-on-migrate mode** — `baselineOnMigrate=true` tells Flyway to skip migrations if the `flyway_schema_history` table already exists, treating existing (pre-Flyway) schema as baseline. Safe for idempotent calls.

3. **Connection pool** — Uses the application's shared `DataSource`. All tenant schemas are on the same peer database and use the same connection pool.

4. **No Spring transaction wrapping at service level** — `createSchemaAndMigrate()` is **not** @Transactional. DDL (CREATE SCHEMA, CREATE TABLE) cannot be rolled back; suppressing @Transactional allows immediate commit (PostgreSQL behavior).


### Code: TenantFlywayMigrationService

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantFlywayMigrationService {

    private final DataSource dataSource;
    private final TenantRepository tenantRepository;

    /**
     * Creates a new schema for the tenant and runs all tenant-scoped Flyway
     * migrations.
     *
     * CRITICAL:
     *  - NOT @Transactional — DDL cannot rollback
     *  - Idempotent — calls with same schemaName succeed on retries
     *  - schema names are "s_<tenantId>" (e.g., "s_456")
     */
    public void createSchemaAndMigrate(String schemaName) {
        log.info("Creating schema and running migrations for tenant: {}", schemaName);
        try {
            // 1. CREATE SCHEMA IF NOT EXISTS — PostgreSQL will not error if schema exists
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            }

            // 2. Configure Flyway for this specific schema
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/tenant")
                    .schemas(schemaName)
                    .baselineOnMigrate(true)  // Skip migrations if history exists
                    .load();

            // 3. Run migrations (V1-V20 per PROJECT_CODEBASE_REFERENCE.txt)
            // If any migration V1-V20 fails, Flyway throws exception (caught below)
            int migrationsRun = flyway.migrate();
            log.info("Successfully created schema and ran {} migrations for: {}",
                     migrationsRun, schemaName);

        } catch (Exception e) {
            log.error("Failed to create schema for tenant: {}", schemaName, e);
            throw TenantException.schemaError(schemaName, e.getMessage());
        }
    }

    /**
     * Drops a tenant schema — used in tests only.
     * DANGER: Irreversible operation; clears all tenant data.
     */
    public void dropSchema(String schemaName) {
        log.warn("Dropping schema: {}", schemaName);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Schema dropped: {}", schemaName);
        } catch (SQLException e) {
            log.error("Failed to drop schema: {}", schemaName, e);
            throw new RuntimeException(e);
        }
    }
}
```


### Code: TenantFlywayMigrationService Integration Point

Called from `SignupService.signup()` in the following context:

```java
// SignupService.java (excerpt)
public AuthResponse signup(SignupRequest request) {
    // 1. Validate email uniqueness
    if (tenantRepository.existsByOwnerEmail(request.getEmail())) {
        throw AuthException.emailAlreadyExists(request.getEmail());
    }

    // 2. Generate schema name (e.g., "s_456")
    String schemaName = generateSchemaName();

    // 3. Create Tenant row in public schema
    Tenant tenant = saveTenant(request, schemaName, provisionalSlug);
    // tenant.id = 456, tenant.schemaName = "s_456"

    // 4. PROVISION SCHEMA — this is where TenantFlywayMigrationService is called
    try {
        tenantFlywayMigrationService.createSchemaAndMigrate(schemaName);
    } catch (Exception e) {
        log.error("Schema provisioning failed for schemaName={}", schemaName, e);
        markTenantFailed(tenant.getId());
        throw e;
    }

    // 5. Set TenantContext BEFORE tenant-schema operations
    TenantContext.setTenantId(tenant.getId());
    TenantContext.setCurrentSchema(tenant.getSchemaName());

    // 6. Create OWNER user in tenant schema
    AuthResponse authResponse = tenantScopedAuthService.createOwnerUserForSignup(request, tenant);

    // 7. Create onboarding record in public schema
    saveOnboardingRecord(tenant.getId());

    return authResponse;
}
```


### Flyway Configuration: FlywayConfig

The public schema migrations (fixture data: plans, permissions, super admin) are configured to run **once at application startup**. Tenant migrations are **disabled from auto-configuration** and run exclusively on-demand via `TenantFlywayMigrationService`.

```java
@Configuration
public class FlywayConfig {

    /**
     * Public schema migrations run once at startup.
     * Initializes plans, permissions, audit_log_template table.
     */
    @Bean
    public Flyway publicFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/public")
                .schemas("public")
                .baselineOnMigrate(true)
                .load();
    }
}
```

**In application.yaml:**

```yaml
spring:
  flyway:
    enabled: false  # CRITICAL: Disable auto-config to prevent Spring from scanning both directories
    # Tenant migrations run explicitly via TenantFlywayMigrationService, not auto-scan
```

**Why `enabled: false`?**

If Flyway auto-configuration is enabled, Spring scans all migration locations and runs ALL (public + tenant) migrations on startup. This would attempt to migrate 20 tenant migrations into the public schema, causing SQL errors. By disabling auto-config:

- **publicFlyway() bean** is explicitly instantiated with location="public" only
- **Tenant migrations** are never touched until `TenantFlywayMigrationService.createSchemaAndMigrate()` is called per tenant

This provides **explicit control** over which migrations run where and when.


### Tenant Schema Naming

Schema names follow the convention `"s_{tenantId}"` (e.g., `"s_456"` for tenant ID 456).

**Why quoted?** PostgreSQL identifiers longer than 63 characters are truncated; quotes are required for identifiers with special characters. Using tenant ID directly ensures:

- Unique schema per tenant
- Predictable name derivation from Tenant.id
- No slug/url-friendly name conflicts
- Simple reverse lookup: given schema name, extract tenant ID

**Schema name is stored in** `Tenant.schemaName` (unique column in public schema).


### Tenant Migrations: V1-V20

Each new tenant schema receives the full set of 20 migrations:

| Version | Purpose | Key Tables |
|---------|---------|-----------|
| V1 | Create roles | `roles` |
| V2 | Create users | `users` |
| V3 | Create refresh tokens | `refresh_tokens` |
| V4 | Create role-permission join | `role_permissions` |
| V5 | Create subscriptions | `subscriptions` |
| V6 | Create usage records | `usage_records` |
| V7 | Create usage summaries | `usage_summaries` |
| V8 | Create invoices | `invoices` |
| V9-V20 | Domain tables & indices | payments, outbox, line_items, etc. |

See [multi-tenancy-strategy.md](../01-authentication/multi-tenancy-strategy.md) for the complete migration inventory.

**Default roles seeded:** Migrations V1-V4 include SQL INSERT statements that create OWNER, ADMIN, EMPLOYEE default roles per tenant schema immediately after schema creation. Each tenant schema starts with a clean, pre-populated RBAC model.


## Flow Diagrams

### Tenant Signup → Provisioning → Activation

```
User clicks "Sign Up"
    ↓
POST /api/auth/signup (request = {name, email, password})
    ↓
SignupService.signup()
    ├─ Email uniqueness check (public schema lookup)
    ├─ Generate schemaName = "s_{tenantId}"
    ├─ saveTenant() → Tenant row in public schema
    │  (status = PENDING_ONBOARDING, onboarding_step = 0)
    ├─ TenantFlywayMigrationService.createSchemaAndMigrate(schemaName)
    │  ├─ CREATE SCHEMA IF NOT EXISTS "s_{tenantId}"
    │  ├─ Flyway.configure().locations(tenant).schemas(schemaName)
    │  └─ Flyway.migrate() [runs V1-V20]
    │     ├─ V1: roles table + INSERT default roles
    │     ├─ V2: users table
    │     ├─ V3-V9: subscription, invoice, payment tables, etc.
    │     └─ V20: final constraints, indices
    ├─ TenantContext.setTenantId(tenantId)
    ├─ TenantContext.setCurrentSchema(schemaName)
    ├─ TenantAuthService.createOwnerUserForSignup()
    │  └─ INSERT User into tenant schema
    ├─ saveOnboardingRecord() → Tenant_onboarding row in public schema
    ├─ FireWelcomeNotification()
    └─ Return JWT + AuthResponse
           ↓
User receives JWT, makes authenticated requests
```



### Provisioning Failure Recovery

```
TenantFlywayMigrationService.createSchemaAndMigrate(schemaName)
    ├─ CREATE SCHEMA succeeds
    ├─ Flyway.migrate() called
    │  ├─ V1-V10 succeed
    │  └─ V11 FAILS (e.g., duplicate key constraint, syntax error)
    │     └─ Exception thrown
    ├─ TenantException.schemaError() caught
    ├─ SignupService catches, calls markTenantFailed(tenantId)
    │  └─ Tenant.status = ONBOARDING_ABANDONED
    └─ Exception propagated to controller, HTTP 500 returned

RECOVERY OPTIONS:
  Option 1: User retries signup (same email)
    ├─ Email already exists, AuthException.emailAlreadyExists() thrown
    └─ User must contact support or use different email

  Option 2: Support manually intervenes
    ├─ Admin calls TenantFlywayMigrationService.dropSchema(schemaName)
    ├─ Admin marks Tenant as DELETED via TenantRepository
    └─ User retries signup
```


## Code References

| Component | Location | Tag | Purpose |
|-----------|----------|-----|---------|
| TenantFlywayMigrationService | [src/main/java/com/mtbs/tenant/service/TenantFlywayMigrationService.java](../../src/main/java/com/mtbs/tenant/service/TenantFlywayMigrationService.java) | [TEN-12] | Schema provisioning orchestration |
| FlywayConfig | [src/main/java/com/mtbs/config/FlywayConfig.java](../../src/main/java/com/mtbs/config/FlywayConfig.java) | [CFG-4] | Public schema migrations on startup |
| SignupService | [src/main/java/com/mtbs/auth/service/SignupService.java](../../src/main/java/com/mtbs/auth/service/SignupService.java) | [AUTH-16] | Signup orchestration (calls provisioning) |
| Tenant Entity | [src/main/java/com/mtbs/tenant/entity/Tenant.java](../../src/main/java/com/mtbs/tenant/entity/Tenant.java) | [TEN-1] | Stores schemaName (unique) |
| Tenant Migrations | [src/main/resources/db/migration/tenant/](../../src/main/resources/db/migration/tenant/) | [SQL-2-*] | V1-V20 migration scripts |
| Public Migrations | [src/main/resources/db/migration/public/](../../src/main/resources/db/migration/public/) | [SQL-1-*] | V1-V8 public schema fixtures |
| Test Helper | [src/test/java/com/mtbs/support/TestSchemaHelper.java](../../src/test/java/com/mtbs/support/TestSchemaHelper.java) | [TST-1] | Schema provisioning for integration tests |


## Rules & Constraints

### Schema Naming Rules

1. **Format must be valid PostgreSQL identifier** — Schema names use lowercase `s_<tenantId>` pattern. No special characters or spaces. PostgreSQL limits identifier length to 63 characters; scheme names use only digits and `_`, so no truncation risk for reasonable tenant IDs.

2. **`CREATE SCHEMA IF NOT EXISTS` is idempotent** — Calling CREATE SCHEMA with IF NOT EXISTS twice for the same schema succeeds both times. The second call becomes a no-op. This ensures retry safety.

3. **Schema names must be globally unique per database** — PostgreSQL does not allow duplicate schema names; unique constraint is enforced at database level. Tenant.schemaName is also marked UNIQUE in the public schema, providing application-level uniqueness verification.


### Flyway Constraints

4. **Migration versions must be unique within a schema** — Flyway enforces that V1, V2, … V20 are versioned sequentially per schema. If a migration file is edited post-deployment, Flyway detects the checksum change and fails. This ensures migration history consistency.

5. **`baselineOnMigrate=true` skips all migrations if history exists** — If Flyway's migration history table already exists in the target schema, Flyway treats the schema as at baseline (migration version 0) and skips running migrations. This prevents double-migration on retry calls. Baseline mode is idempotent-safe.

6. **Flyway instances are isolated per schema call** — Each `TenantFlywayMigrationService.createSchemaAndMigrate()` instantiates a new Flyway bean. Flyway state is never persisted across calls, preventing cross-tenant contamination or shared state issues.


### Transaction Integrity

7. **Schema creation (DDL) cannot be rolled back** — PostgreSQL commits DDL immediately; it is not part of the transaction. Wrapping `CREATE SCHEMA` in @Transactional provides no rollback guarantee. SignupService separates public schema writes (transactional, rollbackable) from schema creation (immediate), and marks Tenant as `ONBOARDING_ABANDONED` on failure.

8. **Tenant row must be created BEFORE schema provisioning** — If provisioning fails, the Tenant row remains in the public schema with status=ONBOARDING_ABANDONED. This allows diagnosis (via admin queries) and potential retry/cleanup.


## Failure Scenarios & Handling

### 1. Schema Already Exists

**Symptom:** `TenantFlywayMigrationService.createSchemaAndMigrate()` called twice for same tenant.

**Root Cause:** Retry on network timeout or duplicate signup attempt with same email.

**Handling:** `CREATE SCHEMA IF NOT EXISTS` succeeds silently. Flyway's `baselineOnMigrate=true` skips migrations if history exists. Call is fully idempotent; no error.

**Mitigation:**
- Email uniqueness check in signup prevents duplicate Tenant rows
- IF NOT EXISTS + baselineOnMigrate=true + unique constraints = idempotent operations


### 2. Flyway Migration Fails Mid-Stream

**Symptom:** SignupService.signup() throws exception; Tenant status = ONBOARDING_ABANDONED.

**Root Cause:** SQL syntax error in migration V7, incompatible column constraint, missing dependency table, etc.

**Handling:** Flyway rolls back the partial transaction (within a single migration). Schema remains in inconsistent state (some tables exist, some don't). TenantFlywayMigrationService.dropSchema() can clean up for retry.

**Mitigation:**
- All 20 migrations are tested in CI/CD before deployment
- Migration files are version-controlled and reviewed
- On production, support must manually intervene:
  1. Identify failed migration from logs
  2. Manually fix migration or database state
  3. Call dropSchema() to clean up
  4. User retries signup


### 3. Connection Pool Exhaustion

**Symptom:** `getConnection()` timeout; SignupService.signup() throws `SQLException`.

**Root Cause:** All pool connections in use; no available connection to create schema.

**Handling:** DataSource throws exception. SignupService catches, marks Tenant as ONBOARDING_ABANDONED, returns HTTP 500.

**Mitigation:**
- Monitor connection pool usage in production (alerts on >80% utilization)
- Increase pool size in application.yaml (HikariCP default 10 connections)
- Implement connection pool metrics/dashboards


### 4. PostgreSQL Quota or Permissions Error

**Symptom:** `CREATE SCHEMA` fails with permission denied or disk quota exceeded.

**Root Cause:** Database user lacks schema creation privilege, or disk is full.

**Handling:** PostreSQL returns error code. TenantFlywayMigrationService throws TenantException.schemaError(). SignupService marks Tenant as ONBOARDING_ABANDONED.

**Mitigation:**
- Verify database user has CREATE privilege in bootstrap scripts
- Monitor disk usage; alert on >90% utilization
- Test disaster recovery (schema creation on secondary/replica)


### 5. Schema Name Collision (PostgreSQL Constraint Violation)

**Symptom:** INSERT Tenant with schemaName="s_456" fails; unique constraint violated.

**Root Cause:** Same tenant ID has been provisioned before (should not happen; indicates data corruption or bug).

**Handling:** PostgreSQL unique constraint throws exception. TenantRepository.save() fails. SignupService catches and re-throws as ResourceException.

**Mitigation:**
- Tenant IDs are auto-generated by database sequence; collisions impossible
- Unique constraint on schemaName provides safety check


## Edge Cases & Special Handling

### Concurrent Tenant Signup

**Scenario:** Two signup requests (different emails) arrive simultaneously.

**Behavior:**
- Request 1: Creates Tenant with ID 456, schemaName="s_456"
- Request 2: Creates Tenant with ID 457, schemaName="s_457"
- Both call createSchemaAndMigrate() in parallel

**Isolation:**
- PostgreSQL isolates execution at schema level
- Each request's Flyway instance targets a different schema ("s_456" vs "s_457")
- No conflicts; migrations run independently

**Result:** Both tenants provisioned successfully in parallel.

**Monitoring:** Log timestamps and schemaName to correlate requests and diagnose slow provisioning (<1s typical on modern hardware).


### Tenant Schema Rename/Reassignment

**Not supported.** Schema names are immutable once assigned (stored in Tenant.schemaName and referenced throughout the application).

**Reason:** Schema names are hardcoded in TenantContext.setCurrentSchema(); changing the name would require updating all tenant-scoped code, migrations, and configuration.

**If rename is required:** Support must manually:
1. Create new schema with new name
2. Use PostgreSQL `pg_dump` to clone tables from old schema
3. Update Tenant.schemaName to new schema name
4. Verify all routes point to new schema
5. Drop old schema

This is a rare operational task, not a user-facing feature.


### Test Schema Cleanup

In tests, `TenantFlywayMigrationService.dropSchema()` is called in @AfterEach or @AfterAll to clean up test schemas. This ensures test isolation.

```java
@AfterEach
void cleanup() {
    flywayMigrationService.dropSchema("s_test_123");
}
```

**Why `DROP SCHEMA CASCADE`?** Cascades to all dependent objects (tables, indices, views). Ensures complete cleanup without manual table drop.


## Known Issues & Limitations

### 1. Long-Running Migrations Block Other Tenants

**Issue:** If a migration takes 30 seconds and connection pool has only 10 connections, concurrent sign-ups may timeout waiting for available connection.

**Mitigation:** Optimize migrations; add indices on large tables asynchronously post-provisioning.

**Planned Fix:** Migrate schema provisioning to async background job (RabbitMQ/Quartz) with configurable concurrency limit.


### 2. No Rollback on Partial Flyway Failure

**Issue:** If V12 migration fails, V1-V11 remain; manual cleanup required.

**Mitigation:** Verify all migrations in dev/staging before production deployment.

**Planned Fix:** Implement dry-run mode for migrations; validate all V1-V20 on application startup.


### 3. Schema History Metadata Is Per-Schema

**Issue:** `flyway_schema_history` table is created in each tenant schema, causing metadata duplication (×number of tenants).

**Mitigation:** Acceptable trade-off; metadata is small (<1MB per 1000 tenants).

**Planned Fix:** Use centralized migration history table in public schema (advanced Flyway configuration).


## Future Improvements

1. **Async Schema Provisioning** — Move provisioning to background job (Quartz scheduler) with configurable concurrency. Return 202 Accepted from signup, send webhook notification when provisioning completes.

2. **Schema Provisioning Dry-Run** — Add `validateOnly` mode to Flyway that checks all migrations without applying DDL.

3. **Incremental Tenant Migration** — Support per-tenant migration versions (V1-V5 for new tenants, V1-V25 for old tenants) without forcing all tenants to V25.

4. **Centralized Migration History** — Move `flyway_schema_history` to public schema, reducing metadata duplication.

5. **Schema Cloning for Backups** — Snapshot schema to separate database for disaster recovery testing.


## Related Documents

- [multi-tenancy-strategy.md](../01-authentication/multi-tenancy-strategy.md) — Schema naming, strategy, 3-step signup flow
- [tenant-context-lifecycle.md](../01-authentication/tenant-context-lifecycle.md) — TenantContext thread-local routing to correct schema
- [system-design.md](../02-core-infrastructure/system-design.md) — Overall architecture, module interaction
- [request-flow.md](../01-authentication/request-flow.md) — Filter chain, where TenantContext is set
- PROJECT_CODEBASE_REFERENCE.txt — Complete code inventory (see tags TEN-1, TEN-12, CFG-4, AUTH-16)
