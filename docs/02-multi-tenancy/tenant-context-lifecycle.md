---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - multitenancy
  - threading
  - threadlocal
  - schema
  - isolation
related_documents:
  - ./multi-tenancy-strategy.md
  - ../01-architecture/request-flow.md
  - ../03-security/authentication.md
---

# Tenant Context Lifecycle

## Executive Summary

`TenantContext` is a **ThreadLocal holder** storing the current tenant's ID and PostgreSQL schema name. It is **set by the JWT filter** at request entry and **cleared in a finally block** at request exit. Hibernate's multitenancy resolver reads this ThreadLocal on every database operation to route SQL to the correct schema. If TenantContext is not cleared, the next request in the same thread pool worker will inherit the previous request's tenant context, causing **silent cross-tenant data leakage** — the #1 security bug in multitenant systems. This document explains the lifecycle in detail.

---

## Context / Problem

### Why ThreadLocal?

Schema-per-tenant multitenancy requires routing SQL queries to different PostgreSQL schemas at runtime. The routing decision must happen **inside Hibernate's connection pooling layer**, where it cannot be passed as a method parameter. ThreadLocal is the standard Java mechanism for request-scoped context in web servers.

### Why Not Request Attributes?

Spring's `RequestContextHolder` (also ThreadLocal-backed) would work, but `TenantContext` is simpler: it has no Spring dependency, can be accessed from utility classes without injecting ServletRequest, and is testable without mocking HTTP objects.

### Why Must It Be Cleared in Finally?

If `TenantContext.clear()` is called after successful request completion, but an exception occurs, the ThreadLocal remains set. The thread is returned to a thread pool, and the **next request served by that thread will see the previous tenant's context**. Database queries will hit the wrong schema, causing data leakage. A finally block ensures cleanup regardless of success/failure/exception.

### Why Not Use Async-Aware ThreadLocal?

MTBS uses Quartz for scheduled jobs (which run off-thread) and Spring Async for email sending (which runs in a different thread). Standard ThreadLocal does not propagate across thread boundaries. For async operations, the calling request thread must **explicitly pass tenantId and schema name** to the spawned task, or use `AsyncContext` wrapper (not currently implemented). This document covers sync request flow only.

---

## Dependencies

### Inbound (Who Sets TenantContext)
- `JwtAuthenticationFilter.doFilterInternal()` — Sets tenantId + schema from JWT claims (AUTH-30)
- `AuthService.login()` — Sets context before delegating to TenantAuthService (AUTH-13)
- `AuthService.refreshAccessToken()` — Sets context before refresh operation (AUTH-13)
- `SignupService.signup()` — Sets context for tenant creation (TEN-14)
- Manual manual context setup in tests

### Outbound (Who Reads TenantContext)
- `CurrentTenantIdentifierResolverImpl.resolveCurrentTenantIdentifier()` — Called by Hibernate on every SQL statement
- `SchemaBasedMultiTenantConnectionProvider.getConnection()` — Gets schema name from TenantContext
- Manual access via `SecurityUtils.getCurrentTenantId()` in business logic
- Repository custom queries that need to log the current tenant

### Configuration
- JPA Hibernate multitenancy: `spring.jpa.properties.hibernate.multiTenancy: SCHEMA`
- Hibernate tenant provider: `SchemaBasedMultiTenantConnectionProvider` (via HibernatePropertiesCustomizer)
- Hibernate tenant resolver: `CurrentTenantIdentifierResolverImpl` (via HibernatePropertiesCustomizer)

---

## Design / Implementation

### ThreadLocal Data Structure

```java
@Slf4j
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_SCHEMA = new ThreadLocal<>();
    private static final ThreadLocal<Long>   CURRENT_TENANT = new ThreadLocal<>();

    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
        log.debug("TenantContext: set tenantId={}", tenantId);
    }

    public static void setCurrentSchema(String schema) {
        CURRENT_SCHEMA.set(schema);
        log.debug("TenantContext: set schemaName={}", schema);
    }

    public static Long getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static String getSchemaName() {
        return CURRENT_SCHEMA.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_SCHEMA.remove();
        log.debug("TenantContext: cleared");
    }

    public static boolean hasTenant() {
        return CURRENT_TENANT.get() != null;
    }
}
```

Two ThreadLocals:
1. **CURRENT_TENANT** (Long) — Tenant ID for business logic queries
2. **CURRENT_SCHEMA** (String) — PostgreSQL schema name (e.g., "s_123") for Hibernate routing

Both must be set together and cleared together.

### Lifecycle: From Request Entry to Response

```
┌──────────────────────────────────────┐
│ HTTP Request arrives at app/ port    │
│ GET /api/billing/invoices            │
│ Authorization: Bearer <jwt>          │
└────────────┬─────────────────────────┘
             │
             ▼ Tomcat worker thread (e.g., Thread-42 from pool)
  ┌──────────────────────────────────────┐
  │ ThreadLocal state (initial)           │
  │ CURRENT_TENANT = null                │
  │ CURRENT_SCHEMA = null                │
  │ (Thread-42 may have served prev req) │
  └──────────────┬───────────────────────┘
                 │
                 ▼ MdcLoggingFilter (not auth-critical)
         [Sets MDC context for logging]
                 │
                 ▼ MdcSecurityEnrichmentFilter (optional)
         [Enriches MDC with security info later]
                 │
                 ▼ JwtAuthenticationFilter.doFilterInternal()
    ┌─────────────────────────────────────────┐
    │ JWT Extraction from header or cookie    │
    │ Parse claims: sub, tenantId, roleId     │
    └────────────┬────────────────────────────┘
                 │
                 ▼ Validation (signature + expiry)
                 │
                 ▼ Extract claims
    ┌──────────────────────────────────────────────┐
    │ Long userId = claims.getSubject()            │
    │ Long tenantId = claims.get("tenantId")       │
    │ String schemaName = schemaCacheService.resolve
    └────────────┬─────────────────────────────────┘
                 │
    ┌────────────────────────────────────────┐
    │ TenantContext.setTenantId(tenantId)    │
    │ TenantContext.setCurrentSchema(schema) │
    └────────────┬─────────────────────────────┘
                 │
    ThreadLocal state NOW:
    CURRENT_TENANT = 123 (Long)
    CURRENT_SCHEMA = "s_123" (String)
                 │
    ┌────────────────────────────────────────────┐
    │ try {                                      │
    │   filterChain.doFilter(req, res)           │
    │   ┌─────────────────────────────────────┐ │
    │   │ [Next filters / endpoints execute]  │ │
    │   │ ┌─────────────────────────────────┐ │ │
    │   │ │ POST /api/billing/invoices      │ │ │
    │   │ │ InvoiceController.create()      │ │ │
    │   │ │ @Transactional service method  │ │ │
    │   │ │ ┌───────────────────────────┐  │ │ │
    │   │ │ │ invoiceRepository.save()  │  │ │ │
    │   │ │ │ Hibernate generates SQL   │  │ │ │
    │   │ │ │ Calls:                    │  │ │ │
    │   │ │ │ CurrentTenantIdentifier   │  │ │ │
    │   │ │ │ Resolver.resolve()        │  │ │ │
    │   │ │ │ Reads: TenantContext.    │  │ │ │
    │   │ │ │ getSchemaName()           │  │ │ │
    │   │ │ │ Returns: "s_123"          │  │ │ │
    │   │ │ │                           │  │ │ │
    │   │ │ │ Hibernate routes conn to  │  │ │ │
    │   │ │ │ schema "s_123"            │  │ │ │
    │   │ │ │ ┌─────────────────────┐   │  │ │ │
    │   │ │ │ │ SQL: INSERT INTO    │   │  │ │ │
    │   │ │ │ │ s_123.invoices {...}│   │  │ │ │
    │   │ │ │ └─────────────────────┘   │  │ │ │
    │   │ │ │ ✓ Data inserted in       │  │ │ │
    │   │ │ │   correct schema         │  │ │ │
    │   │ │ └───────────────────────────┘  │ │ │
    │   │ └─────────────────────────────────┘ │ │
    │   │ HTTP 201 Created                    │ │
    │   │ Response body: {...}                │ │
    │   └─────────────────────────────────────┘ │
    │ } finally {                              │
    │   TenantContext.clear()                  │
    │   ┌─────────────────────────────────┐   │
    │   │ CURRENT_TENANT.remove()         │   │
    │   │ CURRENT_SCHEMA.remove()         │   │
    │   │ ThreadLocal state NOW:          │   │
    │   │ CURRENT_TENANT = null           │   │
    │   │ CURRENT_SCHEMA = null           │   │
    │   └─────────────────────────────────┘   │
    │ }                                        │
    └────────────┬─────────────────────────────┘
                 │
                 ▼ HTTP Response sent
      [Set-Cookie headers, status 201, body]
                 │
                 ▼ Thread-42 returned to Tomcat pool
      (ThreadLocal is now clean)
```

### Error Path: Exception Handling

```
┌──────────────────────────────────────┐
│ HTTP Request arrives                 │
│ POST /api/billing/invoices           │
└────────────┬─────────────────────────┘
             │
             ▼ JwtAuthenticationFilter
  TenantContext.setTenantId(123)
  TenantContext.setCurrentSchema("s_123")
             │
             ▼ try {
             │   filterChain.doFilter()
             │   │
             │   ▼ InvoiceService.create()
             │     @Transactional
             │     │
             │   ▼ invoiceRepository.save(invoice)
             │     │
             │   ▼ Constraint violation:
             │     invoice.amount < 0
             │     └──► @PositiveDecimal validation fails
             │     └──► MethodArgumentNotValidException thrown
             │
             ├─ Caught by GlobalExceptionHandler
             ├─ Logs error with MDC context (tenantId visible in logs)
             ├─ Returns HTTP 400 Bad Request
             │
             ▼ } finally {
                 TenantContext.clear()  ✓ Runs regardless
             }
                 │
                 ▼ HTTP 400 sent to client
                 │
                 ▼ Thread-42 back to pool (clean state)
```

**Key Point**: The finally block runs even if an exception occurs. Without it:

```
┌──────────────────────────────────────┐
│ Request 1: HTTP POST /api/billing... │
└────────┬──────────────────────────────┘
         │
         ▼ JwtAuthenticationFilter (NO finally block ❌)
  TenantContext.setTenantId(123)
         │
         ▼ Exception occurs in endpoint
         │
         ▼ Exception propagates out (NO clear() call)
         │
         ▼ HTTP 500 sent
         │
         ▼ Thread-42 back to pool
         │
         ▼ ThreadLocal state:
            CURRENT_TENANT = 123  ← LEAKED!
            CURRENT_SCHEMA = "s_123" ← LEAKED!
         │
    ┌────────────────────────────────────┐
    │ Request 2 (next request on Thread-42)│
    │ HTTP GET /api/admin/tenants        │
    │ Authorization: Bearer <jwt2>       │
    │ (For admin user in superadmin)     │
    └────────────┬───────────────────────┘
                 │
                 ▼ JwtAuthenticationFilter sets context for admin
                   (But admin JWT has NO tenantId claim...)
                 │
                 ▼ getCurrentTenantIdentifierResolver.resolve()
                   Returns: TenantContext.getSchemaName()
                   ❌ Gets "s_123" from leaked state!
                 │
                 ▼ SQL routes to s_123 (WRONG schema)
                   Admin is now querying tenant 123's data!
                   ⚠️  DATA LEAKAGE
```

This is why the finally block is **MANDATORY**.

### Hibernate Multitenancy Routing

When any Hibernate query executes:

```
1. Hibernate needs a database connection
2. Calls: MultiTenantConnectionProvider.getConnection(tenantIdentifier)
3. Which calls: CurrentTenantIdentifierResolver.resolveCurrentTenantIdentifier()
4. Which reads: TenantContext.getSchemaName()
5. Executes: SET search_path TO "s_123", public
6. Returns connection to tenant's schema
7. Query executes in s_123, not in public
```

**Example SQL**:
```sql
-- Without proper schema context (WRONG):
INSERT INTO invoices (tenant_id, amount) VALUES (456, 100);
-- Would hit public schema table, wrong tenant

-- With proper schema context (RIGHT):
SET search_path TO "s_123", public;
INSERT INTO invoices (tenant_id, amount) VALUES (456, 100);
-- Hits s_123.invoices (specific tenant's table)
```

### Cache Lookups During TenantContext Setup

The JWT filter resolves schema name from Redis cache:

```
Long tenantId = jwtTokenProvider.getTenantIdFromToken(jwt);
   ↓
String schemaName = schemaCacheService.resolveSchemaName(tenantId);
   ├─ Redis lookup: "tenantId:123" → "s_123"
   ├─ If cache miss:
   │   └─ TenantRepository.findById(123).getSchemaName()
   │      └─ Query public schema (no tenant context needed yet)
   └─ Returns schema name
   ↓
TenantContext.setCurrentSchema(schemaName);
```

This is correct: at the time of resolving schema name, TenantContext is not yet set, so queries hit the public schema (by default). After schema is known, TenantContext is set and all subsequent queries hit the tenant schema.

---

## Flow

See Lifecycle: From Request Entry to Response ASCII art above.

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `TenantContext` | [SHARED-1] | `setTenantId(tenantId)` | Store tenant ID in ThreadLocal |
| `TenantContext` | [SHARED-1] | `setCurrentSchema(schema)` | Store schema name in ThreadLocal |
| `TenantContext` | [SHARED-1] | `getTenantId()` | Retrieve tenant ID from thread |
| `TenantContext` | [SHARED-1] | `getSchemaName()` | Retrieve schema name from thread |
| `TenantContext` | [SHARED-1] | `clear()` | Remove both ThreadLocals (cleanup) |
| `CurrentTenantIdentifierResolverImpl` | [SHARED-3] | `resolveCurrentTenantIdentifier()` | Called by Hibernate; returns schema from TenantContext or defaults to "public" |
| `SchemaBasedMultiTenantConnectionProvider` | [SHARED-2] | `getConnection(tenantIdentifier)` | Executes SET search_path for the given schema |
| `JpaConfig` | [APP-2] | `hibernatePropertiesCustomizer()` | Registers Hibernate multitenancy provider + resolver as beans |
| `JwtAuthenticationFilter` | [AUTH-30] | `doFilterInternal()` (try-finally block) | Sets and clears TenantContext; see authentication.md [AUTH-30] |

---

## Rules / Constraints

1. **TenantContext MUST be cleared in a finally block, NEVER in the try block or immediately after doFilter()** — Exceptions do not prevent the finally block from executing. Any other pattern risks the next request inheriting leaked context.

2. **Synchronized services MUST NOT store tenantId in instance fields** — ThreadLocal is per-thread, not per-instance. If a service caches `this.currentTenantId = TenantContext.getTenantId()` at construction time, the value is stale for async operations. Always call `TenantContext.getTenantId()` at method time.

3. **ThreadLocal values MUST be removed when spawning background threads** — Quartz jobs and Spring @Async methods run in different threads. The spawned thread does NOT inherit parent ThreadLocals (by default). Async tasks must be passed `tenantId` and `schemaName` explicitly or wrapped in `AsyncContext`.

4. **Schema resolution (tenantId → schema) MUST happen BEFORE TenantContext is set** — Redis/DB lookups for schema name should NOT require TenantContext. They should query the public schema. Set TenantContext only after schema name is known.

5. **Default schema is "public" if TenantContext is not set** — `CurrentTenantIdentifierResolverImpl.resolveCurrentTenantIdentifier()` returns "public" if `TenantContext.getSchemaName()` is null. This is correct for public schema queries (auth, tenant lookup) but dangerous if called during request processing without explicit context — always validate context is set before tenant-scoped queries.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| ThreadLocal not cleared after exception | (No exception thrown; silent bug) | Request succeeds, but data lands in wrong schema | Developers must test error paths; enforce finally block code review |
| Application updates tenantId without schema | `NullPointerException` or wrong schema routing | Query might fail or hit public schema | Always set both tenantId AND schemaName together; never set only one |
| Background task does not propagate TenantContext | Task executes in null context, queries public schema | Business data not updated; silently wrong  | Pass tenantId + schema name to task constructor; validate context in task |
| Schema name contains special characters (SQL injection) | SQL syntax error or escape clause malfunction | SQL query fails; table not queryable | Only allow alphanumeric + underscore in schema names (enforced at tenant creation) |
| Cache returns wrong schema for tenantId | Data returned from wrong tenant's schema | Data leakage; user sees another tenant's data | Redis key format must be unique; regular cache expiry + DB fallback |
| getCurrentTenantIdentifierResolver.resolve() called before JWT filter | Returns "public" by default | Unauthenticated query against public schema (correct for auth endpoints) | Public schema should only store Tenant, User, Platform entities (no business data) |

---

## Edge Cases

- **Concurrency**: Two threads in the same worker process (ThreadPool) maintain separate ThreadLocal values. Each HTTP request gets its own thread, so no contention. **However**: If thread pools are shared (rare in Tomcat), ThreadLocal values may leak across requests.

- **Timezone**: TenantContext stores Long/String (no time values). No timezone issues. If audit timestamps are stored, they should be in UTC or explicit timezone (separate concern).

- **Tenant Isolation**: Entire security model depends on TenantContext. If even one request fails to clear it, the next request sees the previous tenant's context. Developers MUST test exception paths (404, 401, 500) to verify context is cleared.

- **Request Scope vs. Application Scope**: TenantContext is request-scoped (ThreadLocal). Application-scoped beans (singletons) MUST NOT cache `TenantContext.getTenantId()` at startup. They must call the method at request time.

- **Nested services**: Service A calls Service B, both read TenantContext. Context is the same on both (ThreadLocal is per-thread, not per-method). No issue.

- **Transaction boundaries**: Transactions are per-thread. If a new transaction is created within a request, TenantContext is still available (ThreadLocal persists). Committing transactions does NOT clear ThreadLocal. Only the filter's finally block clears.

---

## Known Issues / Limitations

1. **ThreadLocal values are not serialized in cluster scenarios** — If a request is forwarded to a different server, ThreadLocal is lost. This is expected (load balancer sticky sessions assumed). No automatic recovery.

2. **No timeout on ThreadLocal cleanup** — If a thread crashes while holding ThreadLocal, it remains until thread is recycled. Tomcat recycles threads periodically, so in practice, this is safe. Very long-lived threads (>hours) might accumulate stale context.

3. **Test fixture pollution** — In unit tests, if TenantContext is set but not cleared, subsequent tests see leaked context. JUnit does NOT automatically clear ThreadLocal. Use `@BeforeEach` to call `TenantContext.clear()` in all tests.

4. **No async propagation to child threads** — `Executor` and `ExecutorService` do NOT copy ThreadLocal to spawned threads. `ForkJoinPool` and `CompletableFuture` have similar issues. Spring `@Async` also doesn't propagate automatically.

5. **JdbcTemplate queries bypass Hibernate multitenancy** — If code uses raw JDBC or Spring's `JdbcTemplate`, the schema routing (SET search_path) is NOT applied. All raw JDBC must be wrapped to set schema manually.

---

## Future Improvements

1. Implement `AsyncContextPropagator` — Wrapper for Spring @Async and Quartz jobs that copies TenantContext to spawned threads automatically.

2. Add ThreadLocal audit trail — Log every TenantContext.setTenantId() call with caller stacktrace. Helps debug context leaks in production.

3. Use `RequestScoped @Bean` instead of ThreadLocal for future refactors — Spring's `@Scope("request")` is thread-safe and cleaner semantically (but has overhead).

4. Add health check endpoint to validate schema routing — Periodically query a known tenant schema and verify results come from that schema (not public).

5. Implement per-request circuit breaker — If schema lookup fails 3 times, force context to public and log alert (degrade gracefully vs. crashing).

---

## Related Documents
- [authentication.md](./authentication.md) — Where TenantContext is first set by JwtAuthenticationFilter
- [multi-tenancy-strategy.md](../02-multi-tenancy/multi-tenancy-strategy.md) — Schema provisioning and tenant isolation strategy
- [request-flow.md](../01-architecture/request-flow.md) — Full HTTP request lifecycle showing TenantContext
- [system-design.md](../01-architecture/system-design.md) — Architecture overview
