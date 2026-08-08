---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - schema-migrations
  - flyway
  - database-versioning
  - sql-scripts
  - tenant-migrations
  - data-integrity
  - backwards-compatibility
related_documents:
  - ./subscription-lifecycle.md
  - ./configuration-reference.md
  - ./observability.md
---

# Schema Migrations: Flyway, Versioning, & Multi-Tenant Migrations

## Executive Summary

**Schema migrations** are version-controlled SQL scripts that evolve the database schema over time. MTBS uses **Flyway** for version management:
- **Public schema**: Shared data (plans, webhooks, audit log)
- **Tenant schemas**: Per-tenant data isolated from each other

This document covers:
1. **Flyway Versioning** — naming conventions, SQL script organization
2. **Public Schema Migrations** — shared infrastructure changes
3. **Tenant Schema Migrations** — replicated across all tenant schemas
4. **Data Integrity** — constraints, foreign keys, indexes
5. **Rollback Strategy** — undo migrations safely
6. **Multi-Tenancy Challenges** — applying migrations to 1000s of tenant schemas

---

## Context / Problem

### Historical Problem: Manual SQL Scripts

**Before Flyway:**
```
Developers run ad-hoc SQL scripts locally:
  ALTER TABLE subscriptions ADD COLUMN cancellation_reason VARCHAR(255);
  
Same script needed on staging + production.
Developer sends script via email: "Please run this on prod, thanks!"

Risks:
- Script accidentally run twice (duplicate column error)
- Script not run on prod (schema mismatch, app crashes)
- Script runs on prod but not staging (inconsistent environments)
- No audit trail: who ran what, when?
- Rollback impossible: no script to revert changes
```

**With Flyway:**
```
Developer creates versioned migration:
  V1.23__Add_cancellation_reason_to_subscriptions.sql
  
Contents:
  ALTER TABLE subscriptions ADD COLUMN cancellation_reason VARCHAR(255);
  
Flyway applies automatically:
- On app startup: checks flyway_schema_history table
- Sees V1.22 last applied, V1.23 not applied
- Applies V1.23 atomically
- Records in flyway_schema_history (audit trail)
- Result: same state on all environments (local, staging, prod)
```

---

## Dependencies

### Inbound (What depends on migrations)
- **Application Startup** — App waits for Flyway to complete before starting
- **Tests** — Integration tests need migrated schema
- **CI/CD Pipeline** — Deployment blocked if migrations fail

### Outbound (What migrations depend on)
- **PostgreSQL** — Database engine executing SQL
- **Flyway** — Manages migration ordering and versioning
- **Configuration** — Datasource URL, credentials

### Configuration
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true  # Initialize if table doesn't exist
    locations: classpath:db/migration
    schemas: public  # Primary schema for public migrations
    sql-migration-prefix: V  # V1, V2, etc.
    sql-migration-separator: __  # V1__description.sql
    sql-migration-suffixes: .sql
    baseline-version: 0
    baseline-description: Initial schema
    validate-on-migrate: true
    fail-on-missing-locations: false
    clean-disabled: true  # Never allow clean in prod!
    placeholder-replacement: true
    placeholders:
      # Can be referenced in SQL: ${tableName}
      tenantSchemaPrefix: t
```

---

## Design / Implementation

### Folder Structure

```
src/main/resources/db/migration/
├── public/                    (Public schema migrations)
│   ├── V1.0__Initial_Schema.sql
│   ├── V1.1__Add_Plans.sql
│   ├── V1.2__Add_Subscriptions.sql
│   ├── V2.0__Add_Invoice_Status_Enum.sql
│   ├── V2.1__Add_Webhook_Events_Table.sql
│   └── ...
└── tenant/                    (Tenant schema migrations)
    ├── V1.0__Initial_Tenant_Schema.sql
    ├── V1.1__Add_Subscriptions.sql
    ├── V1.2__Add_Invoices.sql
    └── ...
```

### Version Naming Convention

**Format:** `V<major>.<minor>__<description>.sql`

```
V1.0__Initial_Schema.sql
     ↑    ↑   ↑ (two underscores separator)
     |    |   └── Kebab-case description
     |    └── Minor version (for related changes)
     └── Major version (breaking/significant changes)

Valid examples:
V1.0__Initial_Schema.sql
V1.1__Add_Plans.sql
V1.2__Add_Subscriptions.sql
V2.0__Rename_Status_To_Subscription_Status.sql  (breaking change)
V2.1__Add_Invoice_Indexes.sql
V3.0__Split_Payments_Into_Separate_Table.sql
```

**Invalid (Flyway will reject):**
V1.0_Initial_Schema.sql  (single underscore)
v1.0__Initial_Schema.sql  (lowercase v)
V1.Initial.Schema.sql  (no dots)

---

## Public Schema Migrations

### V1.0: Initial Schema

**File:** `V1.0__Initial_Schema.sql`

```sql
-- Create enum types
CREATE TYPE subscription_status AS ENUM (
    'TRIALING',
    'ACTIVE',
    'PAST_DUE',
    'CANCELLED',
    'EXPIRED'
);

CREATE TYPE plan_type AS ENUM (
    'FREE',
    'PRO',
    'ENTERPRISE'
);

CREATE TYPE billing_cycle AS ENUM (
    'MONTHLY',
    'ANNUAL'
);

-- Tenants table (multi-tenancy)
CREATE TABLE tenants (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    stripe_customer_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tenants_status ON tenants(status);

-- Plans table (shared across all tenants)
CREATE TABLE plans (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    plan_type plan_type NOT NULL,
    price_monthly NUMERIC(10, 2) NOT NULL CHECK (price_monthly >= 0),
    price_annual NUMERIC(10, 2) NOT NULL CHECK (price_annual >= 0),
    features TEXT,  -- JSON array
    trial_days INTEGER DEFAULT 14,
    max_users INTEGER,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_plans_status ON plans(status);
CREATE INDEX idx_plans_type ON plans(plan_type);

-- Audit log table
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36),
    entity_type VARCHAR(100) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    action VARCHAR(50) NOT NULL,  -- CREATE, UPDATE, DELETE
    old_values TEXT,  -- JSON
    new_values TEXT,  -- JSON
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_tenant_created ON audit_log(tenant_id, created_at DESC);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);
```

### V1.1: Add Webhook Events Table

**File:** `V1.1__Add_Webhook_Events_Table.sql`

```sql
CREATE TABLE webhook_events (
    id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,  -- payment.captured, order.paid
    occurred_at TIMESTAMP NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',  -- PENDING, PROCESSING, COMPLETED, FAILED
    attempt_count INTEGER NOT NULL DEFAULT 0,
    payload TEXT NOT NULL,  -- JSON
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_events_status_occurred ON webhook_events(status, occurred_at);
CREATE INDEX idx_webhook_events_event_type ON webhook_events(event_type);

-- Webhook retry tracking
CREATE TABLE webhook_event_retries (
    id BIGSERIAL PRIMARY KEY,
    webhook_event_id VARCHAR(36) NOT NULL REFERENCES webhook_events(id),
    retry_count INTEGER NOT NULL,
    next_retry_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_retries_next_retry ON webhook_event_retries(next_retry_at);
```

### V1.2: Add Notification Delivery Log

**File:** `V1.2__Add_Notification_Delivery_Log.sql`

```sql
CREATE TABLE notification_delivery_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,  -- USER_REGISTERED, PAYMENT_FAILED
    recipient_email VARCHAR(255) NOT NULL,
    subject VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',  -- PENDING, SENT, DELIVERED, BOUNCED, COMPLAINED, FAILED
    retry_count INTEGER NOT NULL DEFAULT 0,
    failure_reason TEXT,
    correlation_id VARCHAR(36),
    
    -- Delivery tracking
    sent_at TIMESTAMP,
    delivered_at TIMESTAMP,
    bounced_at TIMESTAMP,
    complained_at TIMESTAMP,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_tenant_status ON notification_delivery_log(tenant_id, status);
CREATE INDEX idx_notification_correlation ON notification_delivery_log(correlation_id);
CREATE INDEX idx_notification_bounced ON notification_delivery_log(bounced_at);
```

---

## Tenant Schema Migrations

### Applying to All Tenant Schemas

**Challenge:** MTBS has 1000 tenant schemas (t1_schema, t2_schema, ..., t1000_schema).
A single migration script must apply to all.

**Solution: Tenant Migration Executor**

```java
@Component
public class TenantMigrationExecutor {
    
    private final DataSource dataSource;
    private final TenantRepository tenantRepository;
    
    @PostConstruct
    public void executeTenantMigrations() {
        List<Tenant> tenants = tenantRepository.findAll();
        
        for (Tenant tenant : tenants) {
            String schemaName = "t" + tenant.getId().replaceAll("-", "") + "_schema";
            
            try {
                executeMigrationsForSchema(schemaName);
                log.info("Migrated tenant schema: {}", schemaName);
            } catch (Exception e) {
                log.error("Failed to migrate tenant schema {}: {}", schemaName, e.getMessage());
                // Don't fail app startup, alert manually
                alerting.alertTenantMigrationFailure(tenant.getId(), e);
            }
        }
    }
    
    private void executeMigrationsForSchema(String schemaName) {
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schemaName)
            .locations("classpath:db/migration/tenant")
            .load();
        
        MigrateResult result = flyway.migrate();
        log.info("Flyway result for {}: {} migrations applied",
            schemaName, result.migrationsExecuted);
    }
}
```

### V1.0: Tenant Initial Schema

**File:** `src/main/resources/db/migration/tenant/V1.0__Initial_Tenant_Schema.sql`

```sql
-- This runs on EVERY tenant schema (t1_schema, t2_schema, ...)
-- Placeholders: ${tenantSchemaPrefix} = 't'

CREATE TABLE subscriptions (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    status subscription_status NOT NULL DEFAULT 'TRIALING',
    billing_cycle billing_cycle NOT NULL DEFAULT 'MONTHLY',
    
    -- Period tracking
    current_period_start TIMESTAMP NOT NULL,
    current_period_end TIMESTAMP NOT NULL,
    trial_start TIMESTAMP,
    trial_end TIMESTAMP,
    
    -- Upgrade tracking (2-step flow)
    upgrade_pending_invoice_id VARCHAR(36),
    upgrade_pending_plan_id VARCHAR(36),
    upgrade_pending_razorpay_order_id VARCHAR(36),
    
    -- Cancellation
    cancelled_at TIMESTAMP,
    cancel_at_period_end BOOLEAN DEFAULT FALSE,
    cancellation_reason TEXT,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sub_tenant_user ON subscriptions(tenant_id, user_id);
CREATE INDEX idx_sub_status ON subscriptions(status);
CREATE INDEX idx_sub_period_end ON subscriptions(current_period_end);
CREATE INDEX idx_sub_plan ON subscriptions(plan_id);

-- Invoices
CREATE TABLE invoices (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    subscription_id VARCHAR(36) NOT NULL REFERENCES subscriptions(id),
    
    type VARCHAR(50) NOT NULL,  -- SUBSCRIPTION, UPGRADE, ADJUSTMENT
    status invoice_status NOT NULL DEFAULT 'DRAFT',
    
    amount NUMERIC(10, 2) NOT NULL,
    tax NUMERIC(10, 2) DEFAULT 0,
    total NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'INR',
    
    -- Period
    period_start DATE,
    period_end DATE,
    
    -- Razorpay order
    razorpay_order_id VARCHAR(36),
    razorpay_payment_id VARCHAR(36),
    
    -- Dates
    issued_date DATE NOT NULL,
    due_date DATE,
    paid_date TIMESTAMP,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invoice_sub ON invoices(subscription_id);
CREATE INDEX idx_invoice_tenant_status ON invoices(tenant_id, status);
CREATE INDEX idx_invoice_paid_date ON invoices(paid_date DESC);

-- Payments
CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    invoice_id VARCHAR(36) NOT NULL REFERENCES invoices(id),
    
    amount NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'INR',
    payment_method VARCHAR(50),  -- razorpay, stripe, etc.
    
    razorpay_payment_id VARCHAR(36),
    razorpay_signature VARCHAR(255),
    
    status payment_status NOT NULL DEFAULT 'PENDING',  -- PENDING, AUTHORIZED, CAPTURED, FAILED
    failure_reason TEXT,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_invoice ON payments(invoice_id);
CREATE INDEX idx_payment_razorpay ON payments(razorpay_payment_id);
CREATE INDEX idx_payment_created ON payments(created_at DESC);
```

### V1.1: Add Proration Credits

**File:** `src/main/resources/db/migration/tenant/V1.1__Add_Proration_Credits.sql`

```sql
ALTER TABLE invoices ADD COLUMN credit_applied NUMERIC(10, 2) DEFAULT 0;
ALTER TABLE invoices ADD COLUMN credit_reason VARCHAR(255);

-- Track individual credit sources
CREATE TABLE credit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    subscription_id VARCHAR(36) NOT NULL REFERENCES subscriptions(id),
    invoice_id VARCHAR(36) REFERENCES invoices(id),
    
    credit_amount NUMERIC(10, 2) NOT NULL,
    credit_reason VARCHAR(100) NOT NULL,  -- PRORATION_UPGRADE, REFUND, ADMIN_ADJUSTMENT
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_credit_log_sub ON credit_log(subscription_id);
CREATE INDEX idx_credit_log_invoice ON credit_log(invoice_id);
```

---

## Data Integrity & Constraints

### Foreign Key Constraints

```sql
-- Ensure referential integrity
ALTER TABLE subscriptions 
ADD CONSTRAINT fk_sub_plan 
FOREIGN KEY (plan_id) REFERENCES plans(id);

ALTER TABLE invoices 
ADD CONSTRAINT fk_invoice_sub 
FOREIGN KEY (subscription_id) REFERENCES subscriptions(id);

ALTER TABLE payments 
ADD CONSTRAINT fk_payment_invoice 
FOREIGN KEY (invoice_id) REFERENCES invoices(id);
```

**Trade-off: Referential Integrity vs Performance**

```
With FK: Database enforces data integrity (safe)
         But: DELETE plan checks all subscriptions (slow)
         
Without FK: Application responsible for integrity
            But: Fast deletions

MTBS decision: Use FKs for critical paths (subscription → plan)
               Skip FKs for audit tables (fire-and-forget)
```

### Check Constraints

```sql
-- Prevent invalid data at DB level
ALTER TABLE plans 
ADD CONSTRAINT chk_price_positive 
CHECK (price_monthly >= 0 AND price_annual >= 0);

ALTER TABLE invoices 
ADD CONSTRAINT chk_total_gt_zero 
CHECK (total > 0);

ALTER TABLE subscriptions 
ADD CONSTRAINT chk_period_start_before_end 
CHECK (current_period_start < current_period_end);
```

---

## Rollback Strategy

### Flyway Undo (Enterprise Edition, Paid)

MTBS uses **Open Source Flyway** (free), which doesn't support undo.

**Solution: Revert migrations manually**

```sql
-- If migration went wrong, create a new migration to revert:
-- V1.X__Revert_Previous_Migration.sql

-- Example: Undo "Add cancellation_reason" from V1.23
-- Create V1.24__Revert_Cancellation_Reason.sql:

ALTER TABLE subscriptions DROP COLUMN IF EXISTS cancellation_reason;
```

### Test Migrations First

```bash
# Run migrations in staging to verify
docker-compose -f docker-compose.staging.yml up
→ Flyway applies migrations
→ Test app functionality
→ If OK: promote to prod
→ If broken: fix SQL, create V2.X_Fix.sql, test again
```

### Migration Rollback Procedure

**If production migration fails:**

```
1. Immediate: Revert app deployment to previous version
   (Old app code skips new migrations if not found)

2. Manual: Inspect database, identify issue
   SELECT * FROM flyway_schema_history;
   
3. Fix: Create corrective migration
   V2.X__Fix_Previous_Issue.sql
   
4. Validate: Deploy corrective migration to staging first

5. Apply: Deploy corrective migration to production
```

---

## Monitoring Migrations

### Flyway History Table

```sql
SELECT * FROM flyway_schema_history;

version | description                                  | type | script | installed_by | installed_on | execution_time | success
--------|----------------------------------------------|------|--------|--------------|--------------|----------------|--------
1       | Initial schema                               | SQL  | V1.0   | admin        | 2026-05-17   | 145            | true
2       | Add plans                                    | SQL  | V1.1   | admin        | 2026-05-17   | 89             | true
3       | Add subscriptions                            | SQL  | V1.2   | admin        | 2026-05-17   | 234            | true
...

-- Metrics to track:
- execution_time: Should be <5s for most migrations
- If execution_time > 30s: Migration locking table, may cause downtime
```

### Pre-Migration Checklist

```
Before production migration:
☐ Migration tested in staging
☐ Backup taken (full database dump)
☐ Rollback plan documented
☐ Team notified (ops, support, QA)
☐ Monitoring alerts active (track slow queries)
☐ Maintenance window scheduled (low-traffic time)
```

---

## Multi-Tenancy Challenges

### Challenge 1: Applying Migrations to 1000+ Schemas Sequentially

**Problem:**
```
1000 tenant schemas to migrate
Each migration takes 2 seconds
Total time: 1000 × 2s = 33 minutes!
During this time: New subscriptions stall, user-facing errors
```

**Solution: Parallel Migration**

```java
@Component
public class ParallelTenantMigrationExecutor {
    
    private final DataSource dataSource;
    private final TenantRepository tenantRepository;
    
    @Async
    public void executeTenantMigrations() {
        List<Tenant> tenants = tenantRepository.findAll();
        
        // Parallel stream (default thread pool)
        tenants.parallelStream()
            .forEach(tenant -> {
                String schemaName = getSchemaName(tenant);
                try {
                    executeMigrationsForSchema(schemaName);
                } catch (Exception e) {
                    log.error("Failed to migrate {}: {}", schemaName, e);
                }
            });
    }
}
```

**Result:**
```
With 10 parallel threads:
1000 schemas ÷ 10 threads = 100 schemas per thread
100 × 2s = 200 seconds = 3.3 minutes (acceptable downtime window)
```

### Challenge 2: New Tenants During Migration

**Problem:**
```
Migration for existing tenants is running
New tenant created (t1001_schema)
Does new tenant get migrations applied?
```

**Solution: Idempotent Migrations**

```sql
-- Don't assume table doesn't exist
-- Use CREATE TABLE IF NOT EXISTS

CREATE TABLE IF NOT EXISTS subscriptions (
    id VARCHAR(36) PRIMARY KEY,
    -- ...
);

-- Or drop and recreate
DROP TABLE IF EXISTS subscriptions;
CREATE TABLE subscriptions (
    -- ...
);
```

---

## Known Issues / Limitations

1. **Long Migrations Lock Tables** — If migration takes >30s, locks entire table, blocking transactions. Solution: Avoid online operations, use `ADD COLUMN ... DEFAULT` (instant in Postgres 11+), not `ADD COLUMN ... DEFAULT value` (scans table).

2. **No Partial Rollback** — Can't roll back individual migrations, only revert to previous version. Solution: Always test migrations thoroughly.

3. **Circular Dependencies** — If migration A depends on migration B which depends on A, Flyway fails. Solution: Design migrations sequentially (no cycles).

4. **Version Gaps** — If migration V1.2 missing but V1.3 exists, Flyway fails (assumes gap = corruption). Solution: Don't skip versions.

---

## Future Improvements

1. **Online Schema Changes** — Use Postgres 11+ parallel index creation, zero-downtime column additions.

2. **Migration Validation** — Post-migration tests verify data integrity, no corruption.

3. **Scheduled Migrations** — Apply large migrations during low-traffic windows automatically.

4. **Migration Dry-Run** — Preview migration impact before applying (estimate execution time, lock duration).

---

## Related Documents

- [configuration-reference.md](./configuration-reference.md) — Flyway configuration
- [observability.md](./observability.md) — Monitoring migration execution time
