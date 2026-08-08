# ADR-001: Schema-Per-Tenant Multi-Tenancy Strategy

**Date:** May 2026  
**Status:** Accepted  
**Decision Maker:** Architecture Team  
**Stakeholders:** Backend Team, DevOps, Security, Product

---

## Problem Statement

The Multi-Tenant Billing System needs to isolate tenant data securely. Three approaches exist:

1. **Row-Level Security (RLS)** — Single schema, filter by tenant_id in queries
2. **Schema-Per-Tenant** — Separate PostgreSQL schema per tenant, automatic routing
3. **Database-Per-Tenant** — Separate database instance per tenant

**Key Requirements:**
- ✅ Complete data isolation (no accidental data leaks)
- ✅ Compliance-ready (GDPR, SOC2)
- ✅ Performance at scale (100k+ tenants)
- ✅ Reasonable operational complexity
- ✅ Cost-efficient infrastructure

---

## Options Evaluated

### Option 1: Row-Level Security (RLS)

**Description:** Single PostgreSQL schema with tenant_id column on all tables. Row-Level Security policies enforce filtering at database level.

```sql
-- Single public schema for all tenants
CREATE TABLE subscriptions (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    ...
);

-- RLS Policy: user can only see their tenant's rows
CREATE POLICY tenant_isolation ON subscriptions
    USING (tenant_id = current_setting('app.current_tenant_id')::bigint);

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
```

**Pros:**
- ✅ Simpler infrastructure (1 schema)
- ✅ Easier schema migrations (1 migration)
- ✅ Shared indexes (e.g., on tenant_id + subscription_id)
- ✅ Simpler backup/restore

**Cons:**
- ❌ Query complexity — must include tenant_id in WHERE clause
- ❌ Forgot-tenant-id bugs are catastrophic (data leak)
- ❌ Index bloat (all tenants in same table)
- ❌ Harder to audit compliance (complex RLS policies)
- ❌ Performance impact with millions of rows
- ❌ Cross-tenant queries difficult to prevent

**Risk:** Developers forget `WHERE tenant_id = ?` → data breach

---

### Option 2: Schema-Per-Tenant (SELECTED)

**Description:** Separate PostgreSQL schema per tenant. Application automatically routes connections based on tenant context.

```
Database: multitenant_billing_prod
├── public schema
│   └── Tables: tenants, plans, audit_logs (shared)
├── tenant_acme_corp schema
│   └── Tables: subscriptions, invoices, payments (Acme data only)
├── tenant_beta_inc schema
│   └── Tables: subscriptions, invoices, payments (Beta data only)
└── tenant_gamma_llc schema
    └── Tables: subscriptions, invoices, payments (Gamma data only)
```

**Pros:**
- ✅ Physical data isolation (impossible to query across schemas unintentionally)
- ✅ Each tenant has isolated schema (no WHERE tenant_id needed)
- ✅ Easier compliance audits (data physically separate)
- ✅ Per-tenant schema customization possible (future)
- ✅ Simpler backup strategy (backup one schema = one tenant's data)
- ✅ Scale-out possible (schema → separate server)
- ✅ Queries never accidentally leak to other tenants

**Cons:**
- ❌ Schema management complexity (N schemas to maintain)
- ❌ Each tenant gets identical table structure (slight overhead)
- ❌ Connection routing more complex (Hibernate multi-tenancy config)
- ❌ Schema migrations must run per-tenant (automation needed)

**Risk:** Schema routing misconfigured → queries hit wrong schema

---

### Option 3: Database-Per-Tenant

**Description:** Separate PostgreSQL database instance per tenant.

```
Instance 1: acme_corp_db
├── Tables: subscriptions, invoices, payments (Acme only)

Instance 2: beta_inc_db
├── Tables: subscriptions, invoices, payments (Beta only)

Instance 3: gamma_llc_db
├── Tables: subscriptions, invoices, payments (Gamma only)
```

**Pros:**
- ✅ Strongest isolation (separate database server)
- ✅ No shared resources (each tenant has dedicated compute)
- ✅ Can scale independently
- ✅ Compliance friendly (audit trail per DB)

**Cons:**
- ❌ Operational nightmare (100+ databases to manage)
- ❌ Massive infrastructure cost (1 server per tenant)
- ❌ Backup complexity (1 backup per database)
- ❌ Monitoring/alerting complexity (1 alert per database)
- ❌ Schema migrations distributed (hard to test)
- ❌ Difficult to migrate tenants
- ❌ Not practical at scale (>1000 tenants)

---

## Decision

**SELECTED: Option 2 — Schema-Per-Tenant**

### Rationale

1. **Security (Critical)** — Physical schema isolation eliminates accidental data leaks
2. **Compliance** — Audit trails are per-schema (GDPR compliance easier)
3. **Operational Feasibility** — Automation (Flyway) handles schema management
4. **Performance** — Better than RLS (no WHERE clause needed), cheaper than DB-per-tenant
5. **Developer Experience** — Clear isolation reduces bugs

### Trade-offs Accepted

- **Complexity:** Schema routing in Hibernate (acceptable, well-known pattern)
- **Migrations:** Per-tenant migrations via Flyway (automated)
- **Backup:** Per-schema backup strategy (standard for multi-tenant systems)

---

## Implementation

### Architecture

```
HTTP Request
    ↓
JWT Token (contains tenantId)
    ↓
TenantResolutionInterceptor
    │
    ├─ Extract tenantId from JWT
    ├─ Lookup schemaName from database
    └─ Set TenantContext (ThreadLocal)
    ↓
TenantAwareDataSource
    │
    ├─ Read TenantContext
    └─ Route to correct schema
    ↓
Hibernate MultiTenancy
    │
    ├─ CurrentTenantIdentifierResolver
    ├─ SchemaBasedMultiTenantConnectionProvider
    └─ Execute query in tenant's schema
    ↓
Response (tenant-isolated data)
```

### Key Components

#### 1. TenantContext (ThreadLocal)

```java
public class TenantContext {
    private static final ThreadLocal<Long> tenantId = new ThreadLocal<>();
    private static final ThreadLocal<String> schemaName = new ThreadLocal<>();
    
    public static void setTenantId(Long id) { tenantId.set(id); }
    public static Long getTenantId() { return tenantId.get(); }
    public static void setCurrentSchema(String schema) { schemaName.set(schema); }
    public static String getSchemaName() { return schemaName.get(); }
    public static void clear() {
        tenantId.remove();
        schemaName.remove();
    }
}
```

#### 2. TenantResolutionInterceptor

```java
@Component
public class TenantResolutionInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, 
                           Object handler) throws Exception {
        // 1. Extract tenantId from JWT token
        Long tenantId = extractTenantFromJwt(request);
        
        // 2. Lookup schemaName
        String schemaName = lookupSchemaName(tenantId);
        
        // 3. Set context for this request
        TenantContext.setTenantId(tenantId);
        TenantContext.setCurrentSchema(schemaName);
        
        return true;
    }
    
    @Override
    public void afterCompletion(...) throws Exception {
        TenantContext.clear();  // Clean up ThreadLocal
    }
}
```

#### 3. Hibernate Configuration

```java
@Configuration
public class HibernateMultiTenancyConfig {
    
    @Bean
    public CurrentTenantIdentifierResolver currentTenantIdentifierResolver() {
        return () -> TenantContext.getSchemaName();
    }
    
    @Bean
    public MultiTenantConnectionProvider multiTenantConnectionProvider() {
        return new SchemaBasedMultiTenantConnectionProvider();
    }
}
```

#### 4. Schema Migrations (Flyway)

```
db/migration/
├── public/              # Shared schema (tenants, plans, audit logs)
│   ├── V1.0__Initial.sql
│   └── V1.1__Add_Plans.sql
└── tenant/              # Per-tenant schema (subscriptions, invoices, payments)
    ├── V1.0__Create_Tenant_Schema.sql
    └── V1.1__Add_Indexes.sql

When new tenant created:
1. Flyway creates schema: CREATE SCHEMA tenant_{tenant_id}
2. Runs all V*.sql migrations in tenant/ folder
3. Tenant schema now ready for queries
```

---

## Consequences

### Positive

✅ **Strong Data Isolation**
- Physical separation makes data leaks impossible
- Compliance audits straightforward

✅ **Scalability**
- Can move schema to different server (future)
- Each tenant has isolated compute

✅ **Developer Confidence**
- No accidental cross-tenant queries
- Schema isolation enforced at database layer

✅ **Operational Control**
- Per-tenant backup/restore possible
- Can deactivate tenant by dropping schema

### Negative

❌ **Schema Management**
- N schemas to maintain (100 tenants = 100 schemas)
- Flyway must manage multiple schema paths

❌ **Initial Complexity**
- Hibernate multi-tenancy configuration non-trivial
- TenantContext must be managed carefully

❌ **Cross-Tenant Queries Harder**
- Reporting across tenants requires special handling
- (Platform metrics queries read from public schema)

---

## Tenant Schema Naming

**Convention:** `tenant_{tenant_id}`

```sql
-- Example for tenant_id=123
CREATE SCHEMA tenant_123;

-- Example for tenant_id=456
CREATE SCHEMA tenant_456;
```

**Benefits:**
- Schema name deterministic from tenant_id
- Easy to identify schemas: `SELECT * FROM information_schema.schemata WHERE schema_name LIKE 'tenant_%'`
- Simple restoration: `tenant_456` is clearly tenant 456's data

---

## Migration & Onboarding

### New Tenant Onboarding

```
1. Create Tenant record in public schema
   INSERT INTO public.tenants (id, name, schema_name, ...) 
   VALUES (123, 'Acme Corp', 'tenant_123', ...)

2. Create schema and run migrations
   CREATE SCHEMA tenant_123;
   -- Flyway runs: V1.0, V1.1, ... in tenant_123 schema

3. Schema ready for use
   Queries now route to tenant_123 automatically

4. Load initial data (optional)
   INSERT INTO tenant_123.subscription_plans (...)
   INSERT INTO tenant_123.users (...)
```

### Schema Updates

```
When new features added (e.g., V1.2__Add_Usage_Tracking.sql):
1. Add migration to db/migration/tenant/
2. Deploy application (picks up new migration)
3. Next time Flyway runs: checks all tenant schemas
4. Runs V1.2 in each tenant schema automatically
5. All tenants updated consistently
```

---

## Security Considerations

### Defense-in-Depth

| Layer | Control | How |
|-------|---------|-----|
| **Database** | Schema isolation | Separate schema per tenant |
| **Application** | TenantContext | ThreadLocal prevents sharing |
| **Interceptor** | Route to correct schema | JWT → schemaName mapping |
| **Hibernate** | Multi-tenancy provider | Routes connections correctly |
| **Queries** | No WHERE tenant_id needed | Schema itself is the filter |

### Failure Modes

| Failure | Impact | Mitigation |
|---------|--------|-----------|
| **TenantContext not set** | Query fails (empty result) | Interceptor ensures setting |
| **Wrong schema in JWT** | Query hits wrong tenant | Validate JWT signature |
| **Schema routing misconfigured** | Query hits public schema | Integration tests verify |
| **Schema name collision** | Tenant A accesses Tenant B | Naming convention prevents |

---

## Testing Strategy

### Isolation Tests (CRITICAL)

```java
@Test
void tenantACannotSeeTenantBData() {
    // 1. Set context to Tenant A
    TenantContext.setTenantId(1L);
    TenantContext.setCurrentSchema("tenant_1");
    
    // 2. Create subscription in Tenant A
    Subscription subA = subscriptionService.create(request);
    
    // 3. Switch context to Tenant B
    TenantContext.clear();
    TenantContext.setTenantId(2L);
    TenantContext.setCurrentSchema("tenant_2");
    
    // 4. List subscriptions (should be empty)
    List<Subscription> results = subscriptionRepository.findAll();
    assertThat(results).isEmpty();  // ✅ CRITICAL
    
    // 5. Try to directly fetch Tenant A's subscription
    Optional<Subscription> found = subscriptionRepository.findById(subA.getId());
    assertThat(found).isEmpty();  // ✅ CRITICAL (should not find)
}
```

### Schema Verification Tests

```java
@Test
void eachTenantHasSeparateSchema() {
    // Query PostgreSQL information_schema
    List<String> schemas = jdbcTemplate.queryForList(
        "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'tenant_%'",
        String.class
    );
    
    assertThat(schemas)
        .contains("tenant_1", "tenant_2", "tenant_3")
        .doesNotContain("tenant_1", "tenant_1")  // No duplicates
}
```

---

## Related ADRs

- [ADR-002: Outbox Pattern for Reliable Event Delivery](./ADR-002-outbox-pattern.md)
- [ADR-003: Razorpay 2-Step Payment Verification](./ADR-003-razorpay-2step-payment.md)

---

## References

- Hibernate Multi-Tenancy: https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#multitenacy
- PostgreSQL Schemas: https://www.postgresql.org/docs/14/ddl-schemas.html
- Flyway Multi-Schema: https://flywaydb.org/documentation/concepts/migrations#locations
- Multi-Tenancy Patterns: "Multi-Tenant SaaS Database Design" by Marta Żychlewska

---

## Appendix: Comparison Table

| Aspect | Row-Level Security | Schema-Per-Tenant | Database-Per-Tenant |
|--------|-------------------|-------------------|---------------------|
| **Data Isolation** | Logical | Physical | Physical |
| **Query Complexity** | High (WHERE tenant_id) | Low | Low |
| **Schema Count** | 1 | N | N |
| **Backup Complexity** | High | Medium | High |
| **Compliance Ready** | Fair | Excellent | Excellent |
| **Scale Limit** | 1000s of tenants | 10,000s of tenants | 100s of tenants |
| **Cost** | Low | Medium | High |
| **Developer Safety** | Medium | High | High |
| **Selected** | ❌ | ✅ | ❌ |
