---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - multitenancy
  - postgresql
  - schema
  - isolation
  - flyway
  - security
related_documents:
  - ./schema-provisioning.md
  - ./tenant-context-lifecycle.md
  - ../01-architecture/system-design.md
---

# Multi-Tenancy Strategy

## Executive Summary

MTBS uses **schema-per-tenant PostgreSQL isolation**: each tenant gets a dedicated PostgreSQL schema (`s_1`, `s_2`, etc.), eliminating row-level security bugs and providing hard database-level isolation. The public schema stores platform entities (Tenant, Plan, PlatformAdmin). On signup, `TenantFlywayMigrationService` creates the new schema and runs 20 SQL migrations (users, roles, permissions, subscriptions, invoices, etc.). The Hibernate multitenancy resolver routes every SQL query to the correct schema at runtime via `SET search_path`. Tenant data cannot leak across boundaries without explicit cross-schema joins (not allowed in application code). This strategy trades provisioning complexity for bulletproof isolation.

---

## Context / Problem

### Why Schema-Per-Tenant Over Database-Per-Tenant?

**Database-per-tenant**: Each tenant = separate database (separate credentials, separate backups, separate replication streams). Pros: Maximum isolation, no schema conflicts. Cons: Operational nightmare (28 databases for 28 tenants), expensive hardware, backup complexity, hard to migrate globally.

**Schema-per-tenant** (chosen): All tenants in one PostgreSQL database, each in separate schema. Pros: Single database to manage, single backup strategy, can scale to thousands of tenants, row data cannot accidentally leak (schema isolation). Cons: Still requires careful coding (TenantContext, filters); database-level admin could theoretically query all schemas.

**Row-level security (RLS)**: All tenants in one schema, rows tagged with `tenant_id`, PostgreSQL RLS policies enforce row filtering. Cons: RLS policies are often slow, can be accidentally bypassed, adds complexity to every query.

**Chosen**: Schema-per-tenant because:
1. Hard boundary — no query can accidentally hit two schemas
2. Scales well (one DB, many schemas)
3. Simple operational model (one backup, one replicas set)
4. Testing is easier (drop schema in tests)
5. Performance: no row filtering overhead

### Why Not Database-Per-Tenant?

Would require 28+ PostgreSQL servers for the current customer base. Flyway migrations would need to run on each DB separately. Schema lookups would fail (each DB has different schemas). Tenant switchover would be network round-trip to different DB server. Cost-prohibitive for a SaaS platform.

### Why Not Row-Level Security?

PostgreSQL RLS policies add overhead even for single-tenant queries. They can be accidentally bypassed if a developer forgets to apply a policy. RLS works best for a single table; applying it to 10+ tables (users, roles, invoices, etc.) is error-prone.

### Why Flyway?

Version-controlled SQL migrations: every schema change is a `V{n}__description.sql` file, committed to Git, reviewed in PR, applied consistently. Versioning prevents "did we run this on tenant 2?" confusion. Flyway tracks which migrations have run via a `flyway_schema_history` table in each schema.

---

## Dependencies

### Inbound (Who Calls This)
- `SignupService` → `TenantFlywayMigrationService.createSchemaAndMigrate()` — During tenant signup
- `TenantService.getTenantById()` — Read tenant metadata (TEN-10)
- `TenantService.updateTenant()` — Update tenant name/status
- `TenantRepository.findBySlug()` — Resolve slug to tenant (public schema)
- `SchemaCacheService.resolveSchemaName()` — Lookup: tenantId → schema name (public schema)

### Outbound (What This Calls)
- `Flyway.migrate()` — Execute SQL migrations
- `DataSource.getConnection()` — Get database connection
- `PostgreSQL SET search_path` — Switch to tenant schema
- `TenantRepository` — Query tenants table (public schema)
- `OutboxEventPublisher` — Publish audit events on tenant changes

### Configuration
- `spring.flyway.enabled: false` — Disable Spring Boot auto-migration (we manage it manually)
- `spring.jpa.properties.hibernate.multiTenancy: SCHEMA` — Enable Hibernate multitenancy
- Migration paths: `classpath:db/migration/public`, `classpath:db/migration/tenant`

---

## Design / Implementation

### Schema Naming Convention

Schemas are named **`s_{tenantId}`** where `tenantId` is the Tenant entity's primary key (Long).

Examples:
```
Tenant ID=1   → Schema: s_1
Tenant ID=123 → Schema: s_123
Tenant ID=456789 → Schema: s_456789
```

The schema name is stored in the `tenants.schema_name` column (public schema). 

**Why `s_` prefix?**
- PostgreSQL identifiers starting with digits are not valid (e.g., `"123"` requires quotes, inconvenient)
- Prefix `s_` is mnemonic for **schema**
- Lowercase convention matches PostgreSQL best practices

### Tenant Creation Flow: Signup to Provisioning

```
┌──────────────────────────────────────────────┐
│ POST /api/auth/signup                        │
│ { email, password, businessName, slug, ... } │
└────────────┬─────────────────────────────────┘
             │
             ▼ SignupService.signup()
       ┌─────────────────────────────┐
       │ 1. Validate slug uniqueness │
       │    Query public.tenants     │
       │    WHERE slug = request.slug│
       └────────────┬────────────────┘
                    │
       ┌────────────────────────────────┐
       │ 2. Find next available tenantId│
       │    New Tenant entity (PK auto) │
       │    Flyway will use this ID     │
       └────────────┬───────────────────┘
                    │
       ┌────────────────────────────────┐
       │ 3. Generate schema name        │
       │    schemaName = "s_" + tenantId│
       │    e.g., "s_456"               │
       └────────────┬───────────────────┘
                    │
       ┌────────────────────────────────────────┐
       │ 4. Save Tenant to public schema        │
       │    INSERT INTO public.tenants (...)    │
       │    VALUES (456, 's_456', 'FREE', ...)  │
       │    COMMIT (Tenant row now exists)      │
       └────────────┬───────────────────────────┘
                    │
       ┌────────────────────────────────────────┐
       │ 5. Create schema + run migrations      │
       │    TenantFlywayMigrationService.       │
       │    createSchemaAndMigrate("s_456")     │
       │    ├─ CREATE SCHEMA "s_456"            │
       │    ├─ Flyway.migrate():                │
       │    │  ├─ V1__create_roles.sql          │
       │    │  ├─ V2__create_users.sql          │
       │    │  ├─ ... (20 migrations)           │
       │    │  ├─ V20__create_outbox_events.sql │
       │    └─ Flyway tracks runs in            │
       │       s_456.flyway_schema_history      │
       └────────────┬───────────────────────────┘
                    │
       ┌────────────────────────────────────────┐
       │ 6. Create ROLE_OWNER user              │
       │    Set TenantContext (456, s_456)      │
       │    INSERT INTO s_456.users (...)       │
       │    Hash password (BCrypt)              │
       │    CREATE REFRESH_TOKEN                │
       └────────────┬───────────────────────────┘
                    │
       ┌────────────────────────────────────────┐
       │ 7. Auto-subscribe to Free Plan         │
       │    INSERT INTO s_456.subscriptions     │
       │    (plan_id=FREE, status=ACTIVE)       │
       │    Create first invoice                │
       └────────────┬───────────────────────────┘
                    │
       ┌────────────────────────────────────────┐
       │ 8. Set HttpOnly cookies                │
       │    Return JWT (tenantId=456, ...)      │
       │    TenantContext.clear()               │
       │    HTTP 201 Created                    │
       └───────────────────────────────────────┘
```

### Public Schema: Platform Entities

The `public` schema contains **single-tenant** tables — shared across all tenants:

```sql
-- public.tenants
id           BIGSERIAL PRIMARY KEY
schema_name  VARCHAR UNIQUE        -- "s_123", "s_456"
slug         VARCHAR UNIQUE        -- "acme", "widget-corp"
plan_type    VARCHAR               -- "FREE", "PRO", "ENTERPRISE"
status       VARCHAR               -- "ACTIVE", "SUSPENDED", "INACTIVE"
owner_email  VARCHAR               -- Owner's email
created_at   TIMESTAMPTZ

-- public.plans (pricing tiers)
id              BIGSERIAL PRIMARY KEY
plan_type       VARCHAR UNIQUE        -- "FREE", "PRO"
name            VARCHAR               -- "Free", "Professional"
monthly_price   DECIMAL               -- 0, 999, 9999
trial_days      INT
usage_limits    JSONB                 -- Limits for this plan

-- public.permissions
id          BIGSERIAL PRIMARY KEY
name        VARCHAR UNIQUE            -- "TENANT_VIEW", "BILLING_MANAGE"
description VARCHAR

-- public.platform_admins
id          BIGSERIAL PRIMARY KEY
email       VARCHAR UNIQUE
password    VARCHAR (BCRypt hash)
roles       JSONB or separate table

-- public.audit_logs
id          BIGSERIAL PRIMARY KEY
tenant_id   BIGINT (nullable, nullable = super-admin action)
action      VARCHAR         -- "CREATE", "UPDATE", "DELETE"
entity_type VARCHAR         -- "Tenant", "User", "Invoice"
actor_id    BIGINT
changed_at  TIMESTAMPTZ
changed_data JSONB
```

### Tenant Schema: Business Entities

Each tenant schema (`s_456`) contains isolated business data:

```sql
-- s_456.roles
id          BIGSERIAL PRIMARY KEY
name        VARCHAR UNIQUE           -- "OWNER", "MEMBER", "VIEWER"
permissions (FK to role_permissions)

-- s_456.users
id          BIGSERIAL PRIMARY KEY
email       VARCHAR UNIQUE (per tenant)
password    VARCHAR (BCript hash)
role_id     BIGINT (FK roles.id)
status      VARCHAR                  -- "ACTIVE", "DISABLED"

-- s_456.subscriptions
id          BIGSERIAL PRIMARY KEY
plan_id     BIGINT (FK plans.id in PUBLIC schema)
status      VARCHAR                  -- "ACTIVE", "TRIAL", "EXPIRED"
current_period_start  TIMESTAMPTZ
current_period_end    TIMESTAMPTZ

-- s_456.invoices
id          BIGSERIAL PRIMARY KEY
subscription_id BIGINT (FK subscriptions.id)
status      VARCHAR                  -- "DRAFT", "SENT", "PAID"
amount      DECIMAL
due_date    DATE

[More: customers, products, payments, usage_records, outbox_events, etc.]
```

### Flyway Migration Process (20 Migrations)

Each tenant schema runs the same 20 SQL migration files in `classpath:db/migration/tenant/`:

| File | Purpose |
|------|---------|
| `V1__create_roles.sql` | Create roles table + seed default roles (OWNER, MEMBER, VIEWER) |
| `V2__create_users.sql` | Create users table (email, password, role_id, status) |
| `V3__create_refresh_tokens.sql` | Create refresh_token table for JWT rotation |
| `V4__create_role_permissions.sql` | Create role_permissions join table |
| `V5__create_subscriptions.sql` | Create subscriptions table (plan, status, dates) |
| `V6__create_usage_records.sql` | Create usage_records (raw metric data) |
| `V7__create_usage_summaries.sql` | Create usage_summaries (aggregated) |
| `V8__create_invoices.sql` | Create invoices table (platform billing) |
| `V9__create_invoice_line_items.sql` | Create invoice line item table |
| `V10__create_payments.sql` | Create payments table (Razorpay, status) |
| `V11__create_quartz_tables.sql` | Create Quartz scheduler tables (job history) |
| `V12__seed_roles.sql` | Seed default roles (OWNER, MEMBER, VIEWER) |
| `V13__add_unique_constraint.sql` | Add unique constraint on role_permissions |
| `V14__seed_role_permissions.sql` | Link roles to permissions (RBAC setup) |
| `V15__create_customers.sql` | Create customers table (business module) |
| `V16__create_products.sql` | Create products table (business module) |
| `V17__create_business_invoices.sql` | Create invoices table (tenant bills their customers) |
| `V18__create_business_invoice_item.sql` | Create business invoice line items |
| `V19__create_business_payments.sql` | Create payments table (tenant receives from customers) |
| `V20__create_outbox_events.sql` | Create outbox_events table (transactional event publishing) |

**Flyway History**: Flyway creates `flyway_schema_history` table automatically:
```sql
-- s_456.flyway_schema_history
installed_rank INT
version        VARCHAR     -- "1", "2", "3"
description    VARCHAR     -- "create roles"
type           VARCHAR     -- "SQL"
script         VARCHAR     -- "V1__create_roles.sql"
checksum       INT         -- Validates script not modified
installed_by   VARCHAR
installed_on   TIMESTAMP
execution_time INT         -- ms
success        BOOLEAN
```

If migrations are run twice (e.g., in tests), Flyway skips already-applied migrations.

### Schema Isolation SQL Example

```sql
-- When user logs in with JWT (tenantId=456):
-- TenantContext.setCurrentSchema("s_456")
-- Hibernate calls: SET search_path TO "s_456", public

-- Query: Find user's subscriptions
-- (No WHERE clause needed for tenant filtering — schema isolation!)
SELECT s FROM Subscription s WHERE s.user_id = ? AND s.status = 'ACTIVE'

-- Hibernate generates:
SET search_path TO "s_456", public;
SELECT id, user_id, plan_id, status, ... 
FROM subscriptions 
WHERE user_id = 123 AND status = 'ACTIVE';

-- Query runs ONLY in s_456.subscriptions
-- NOT in public.subscriptions (public schema has no subscriptions table anyway)
-- Data from s_457 or other schemas is IMPOSSIBLE to read


-- Cross-tenant query (would fail):
SELECT * FROM s_456.invoices, s_457.invoices;
-- PostgreSQL error: Cannot query two different schemas in one query
-- (Unless explicitly written; application code never does this)
```

### Schema Caching Lookup

On every request, the JWT filter needs to map `tenantId` → `schemaName`:

```java
// JwtAuthenticationFilter.doFilterInternal()
Long tenantId = jwtTokenProvider.getTenantIdFromToken(jwt);

// Redis cache lookup:
String schemaName = schemaCacheService.resolveSchemaName(tenantId);
// Returns "s_456" from Redis key "tenantId:456:schema"
// Cache miss → SQL query: SELECT schema_name FROM public.tenants WHERE id = 456

// Almost never misses (TTL = 1 hour default)
```

This avoids a public-schema SQL hit on every request.

### Multi-Tenant Tests: Schema Cleanup

In tests, `TenantFlywayMigrationService.dropSchema()` removes schemas:

```
Test 1: Create tenant s_test1, run migrations, assertions, ✓ Pass
Cleanup: DROP SCHEMA s_test1 CASCADE  ← Removes all tables
Test 2: Create tenant s_test2, run migrations, assertions, ✓ Pass
Cleanup: DROP SCHEMA s_test2 CASCADE
Test 3: Next test...
```

Prevents test pollution: each test gets a clean schema.

---

## Flow

See Tenant Creation Flow: Signup to Provisioning ASCII art above.

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `Tenant` | [TEN-1] | Entity mapping to public.tenants table | Represents single tenant row |
| `TenantService` | [TEN-10] | `getTenantById(tenantId)` | Load tenant metadata (schema name, status) |
| `TenantService` | [TEN-10] | `getTenantSchemaInfo()` | Query tenant schema for user/role/subscription counts |
| `TenantService` | [TEN-10] | `updateTenant()` | Update tenant name/status; fires audit event |
| `TenantFlywayMigrationService` | [TEN-13] | `createSchemaAndMigrate(schemaName)` | Create PostgreSQL schema + run 20 migrations |
| `TenantFlywayMigrationService` | [TEN-13] | `dropSchema(schemaName)` | Drop schema (tests only) |
| `FlywayConfig` | [TEN-17] | `publicFlyway(dataSource)` | Bean that runs public-schema migrations on startup |
| `JpaConfig` | [APP-2] | `hibernatePropertiesCustomizer()` | Registers Hibernate multitenancy provider |
| `SignupService` | [TEN-14] | `signup()` | Orchestrates tenant creation, schema provisioning, user seeding |
| `CurrentTenantIdentifierResolverImpl` | [SHARED-3] | `resolveCurrentTenantIdentifier()` | Reads TenantContext.getSchemaName() for Hibernate query routing |

---

## Rules / Constraints

1. **Schema names MUST be stored in the Tenant entity and cached in Redis** — Never compute schema name on the fly during request handling. Always look it up from cache or database upfront. Storing in TenantContext ensures consistency.

2. **Tenant creation MUST write Tenant row to public schema BEFORE running migrations** — If migrations fail (e.g., disk full), the Tenant row can be rolled back. Reversing: never write to tenant schema if Tenant row doesn't exist; queries would target null schema.

3. **All Flyway migration files MUST have unique version numbers** — `V1__`, `V2__`, etc. Duplicate versions cause Flyway to error. Use consecutive integers; never reuse or skip. Renaming old files breaks the history table.

4. **Migrations MUST NOT reference data from other schemas** — Tenant migrations should not SELECT from public schema or other tenant schemas. Migrations are schema-isolated; cross-schema queries will fail if only one schema exists. Use application code (Java) to join data.

5. **Default schema MUST be "public" if no TenantContext is set** — Fallback routing ensures auth endpoints (signup, login) query the public schema. Never let a query run against null or invalid schema.

6. **Schema names MUST NOT contain special characters (only alphanumeric + underscore)** — PostgreSQL identifier rules. Names like `s_123-abc` (with hyphens) require quoting in every SQL statement. Alphanumeric only prevents SQL injection and escaping issues.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| Schema creation fails (disk full) | `TenantException.schemaError()` | 500 Internal Server Error | Retry after freeing disk; Repeating signup will re-attempt migration |
| Flyway migration fails (syntax error in SQL) | `FlywayException` | 500 Internal Server Error | Fix SQL migration, redeploy; Flyway tracks which ran so only new ones run |
| Tenant row exists but schema doesn't (inconsistency) | `NullPointerException` on schema lookup or `PGSQLException` on query | 500 Internal Server Error | Manual intervention: run `TenantFlywayMigrationService.createSchemaAndMigrate()` to recreate |
| Duplicate schema name (rare race condition) | `PSQLException` (unique constraint) | 500 Internal Server Error | Schema name must be unique; check for race condition in signup service |
| User queries from schema s_456 but JWT says s_123 | Silent bug (query succeeds but wrong schema) | Query runs but no data found or data from s_123 | Log access attempts; rebuild TenantContext from JWT claims |
| Flyway version history corrupted (manual DELETE from flyway_schema_history) | `FlywayException` on next migration | 500 Internal Server Error | Restore backup or manually repair flyway_schema_history table |
| Tenant schema dropped manually (not via dropSchema()) | SQL queries fail with "schema does not exist" | 500 Internal Server Error | Recreate schema via `TenantFlywayMigrationService.createSchemaAndMigrate()` |

---

## Edge Cases

- **Concurrency**: Two simultaneous signup requests race to check if a slug is unique. Both see it's available, both create Tenant rows. One fails on unique constraint. Frontend retries; second signup succeeds. Schema provisioning for first Tenant might not complete (app moves on). Recovery: Manual intervention or async retry queue.

- **Timezone**: Schema names and migration timestamps are stored in UTC (TIMESTAMPTZ). No timezone bugs. Tenant creation timestamps should use server timezone (e.g., US/Eastern). Migration applies to all tenants uniformly (no timezone variations).

- **Tenant Isolation**: Entire security model depends on schema isolation. If a query explicitly includes `s_123` in the table reference (e.g., `SELECT * FROM s_123.invoices`), it bypasses search_path and hits the wrong schema. Application code MUST NEVER hard-code schema names in queries.

- **Empty tenant schema**: If a tenant has been created but no users/subscriptions exist, queries return empty result sets (correct behavior). No error. Subsequent operations (user creation) succeed.

- **Test data not isolated**: If tests create data in s_test1 but don't drop the schema afterward, the next test run will inherit it. Use `@BeforeEach` and `@AfterEach` to guarantee cleanup.

---

## Known Issues / Limitations

1. **No automated cross-schema joins** — If business logic needs data from both public.plans and s_456.subscriptions, it must fetch both separately in Java and join in memory. No cross-schema SQL joins (possible but not recommended). Workaround: Denormalize plan data into each schema.

2. **Flyway baseline mechanism is complex** — `baselineOnMigrate(true)` marks all existing tables as baseline before running migrations. If a manually-created table exists but hasn't been migrated, Flyway might skip it. Solution: Always run migrations AFTER schema creation, not before.

3. **No rollback for failed migrations** — If a migration fails partway through (e.g., ALTER TABLE on a huge table times out), Flyway marks it failed. Retrying runs the next migration, but the partial migration state might corrupt the schema. Manual recovery needed.

4. **Schema names are not globally unique across clusters** — If two deployments (production and staging) run against the same PostgreSQL instance, they might create tenant `s_1` independently. Namespace collisions in tests/staging are common. Solution: Use different PostgreSQL databases for each environment.

5. **Dropped tenants leave schema history behind** — When `dropSchema(s_456)` is called, the PostgreSQL schema is deleted but no log entry is created. If the schema is accidentally recreated, Flyway's history table is reset. Traceability is lost.

---

## Future Improvements

1. Implement automated schema backup before migrations — Create backup snapshot before running Flyway on all schemas. If migration fails, restore from backup.

2. Add schema versioning API endpoint — Expose which Flyway migration version each tenant schema is at. Helps diagnose schema mismatch bugs.

3. Implement cross-schema read replicas — Allow read queries to fetch plan data from public + tenant data in one query (via Postgres foreign tables or materialized views).

4. Add schema isolation tests — Automated tests that verify queries in s_456 cannot read s_457 data (even with explicit SQL directives).

5. Implement multi-schema sharding — If Postgres becomes bottleneck, shard tenants across multiple PostgreSQL databases, each with its own public schema. Application routes tenant requests to correct shard via tenantId modulo.

---

## Related Documents
- [tenant-context-lifecycle.md](./tenant-context-lifecycle.md) — ThreadLocal routing from schema name to Hibernate
- [authentication.md](../03-security/authentication.md) — JWT filter sets TenantContext with schemaName from this module
- [schema-provisioning.md](./schema-provisioning.md) — Detailed provisioning steps and recovery procedures
- [cross-tenant-safety.md](./cross-tenant-safety.md) — Testing and validation of isolation
- [system-design.md](../01-architecture/system-design.md) — Architecture overview
- [request-flow.md](../01-architecture/request-flow.md) — Full HTTP lifecycle showing schema routing
