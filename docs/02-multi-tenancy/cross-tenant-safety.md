---
version: 1.0
date: 2026-05-14
author: Manoj Pandi
status: CRITICAL - Production Ready
tags:
  - security
  - multitenancy
  - data-isolation
  - sql-injection
  - audit-logging
related_documents:
  - ../09-security/authentication.md
  - ../09-security/authorization.md
  - ./jwt-token-lifecycle.md
  - ../04-core-architecture/multitenancy.md
---

# Cross-Tenant Safety (CRITICAL)

## Executive Summary

**CRITICAL DOCUMENT:** This document explains the security patterns that prevent cross-tenant data leakage in MTBS. Failures here result in catastrophic data breaches. It covers TenantContext lifecycle, query routing, soft deletion, audit logging, and SQL injection prevention.

---

## Context & Problem

### Threat Model

```
Attacker Goal: Access another tenant's data
  ├─ Tenant A user (userId=123, tenantId=456)
  ├─ Tenant B user (userId=789, tenantId=999)
  │
  └─ Attack vectors:
      ├─ Modify JWT to claim tenantId=999 → Caught by signature validation
      ├─ Query User 789 from schema 456 → Returns no results (schema isolation)
      ├─ Modify URL to /invoices/789 → Tenant B's schema, rejected by SQL query
      ├─ Inject SQL to bypass WHERE clause → Parameterized queries prevent
      └─ Access data without authentication → JWT validation catches
```

### Why Multi-Level Protection

```
Single point of failure = data breach
  ├─ Rely only on JWT tenant claim → Signature forged or token compromised
  ├─ Rely only on schema isolation → SQL injection bypasses schema
  ├─ Rely only on WHERE clauses → Query builder generates wrong clause
  │
Multi-level defense:
  ├─ JWT validation → Tenant identity
  ├─ TenantContext verification → Thread-local isolation
  ├─ Schema routing → Database-level isolation
  ├─ SQL WHERE clauses → Query-level isolation
  └─ Parameterized queries → SQL injection prevention
```

---

## Layer 1: JWT Validation & TenantContext

### Request Entry Point

```
1. HTTP Request
   POST /api/v1/invoices?limit=100
   Cookie: accessToken=eyJhbGc...

2. JwtAuthenticationFilter:
   │
   ├─ Extract JWT from cookie
   ├─ Validate signature (catches tampering)
   ├─ Validate expiration (catches old tokens)
   │
   ├─ Extract claims:
   │   ├─ userId: 123
   │   ├─ tenantId: 456             ← CRITICAL
   │   ├─ roleId: 2
   │   └─ tokenVersion: 5
   │
   ├─ **Set TenantContext:**
   │   ├─ TenantContext.setTenantId(456)
   │   ├─ TenantContext.setCurrentSchema("s_456")
   │   └─ Thread-local variables now populated
   │
   ├─ Load permissions from cache
   └─ Create SecurityContext with UserPrincipal
```

### Critical: Finally Block

```java
// THIS IS CRITICAL — Must clear TenantContext after each request
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) 
            throws ServletException, IOException {
        try {
            // 1. Extract and validate JWT
            String jwt = getJwtFromRequest(request);
            
            // 2. Extract claims
            Long tenantId = jwtTokenProvider.getTenantIdFromToken(jwt);
            
            // 3. **SET TenantContext**
            TenantContext.setTenantId(tenantId);
            TenantContext.setCurrentSchema(schemaName);
            
            // 4. Proceed to controller
            filterChain.doFilter(request, response);
            
        } catch (Exception e) {
            handleError(e, response);
        } finally {
            // **CRITICAL: Clear TenantContext**
            TenantContext.clear();  // ← MUST NOT BE OMITTED
            
            // Reason:
            // - Thread pool reuses threads
            // - Next request gets same thread
            // - If not cleared: Next request sees PREVIOUS tenant's context
            // - Result: XSS to wrong tenant's schema
        }
    }
}
```

**Failure scenario (missing finally):**

```
Request 1 (Tenant A):
  Thread-A → set TenantContext(tenantId=456)
  ├─ Controller processes
  └─ Response sent

Request 2 (Tenant B, same Thread-A):
  Thread-A → set TenantContext(tenantId=999)
  ├─ BUT: Previous context still in thread-local
  ├─ If TenantContext.clear() was omitted
  ├─ And new request's JWT processing fails
  ├─ Old tenantId=456 still in TenantContext
  └─ Database queries use WRONG tenant's schema
     → Data leak to Tenant B
```

**Protection: ALWAYS clear in finally block**

---

## Layer 2: Schema Isolation

### Database Schema Structure

```
PostgreSQL:
  ├─ public schema:
  │   ├─ tenants
  │   ├─ plans
  │   ├─ platform_admins
  │   ├─ audit_logs
  │   └─ (platform-wide data)
  │
  ├─ s_456 schema: (Tenant A)
  │   ├─ users
  │   ├─ roles
  │   ├─ permissions
  │   ├─ invoices
  │   ├─ payments
  │   ├─ subscriptions
  │   └─ (Tenant A data only)
  │
  ├─ s_999 schema: (Tenant B)
  │   ├─ users
  │   ├─ roles
  │   ├─ invoices
  │   └─ (Tenant B data only)
  │
  └─ s_1001 schema: (Tenant C)
      └─ (Tenant C data only)
```

### Query Routing

```
ApplicationRequest:
  GET /api/v1/invoices

1. TenantContext.getTenantId() → 456

2. CurrentTenantIdentifierResolver:
   ├─ Fetch TenantContext.getSchemaName()
   ├─ Return: "s_456"
   └─ Hibernate routes queries to s_456 schema

3. Repository.findAll():
   ├─ SELECT * FROM s_456.invoices
   │  └─ (NOT public.invoices)
   │
   ├─ Even if attacker queries:
   │  └─ SELECT * FROM s_999.invoices
   │  └─ Hibernate ignores — uses s_456
   │
   └─ Result: Only Tenant A's invoices returned
```

### Schema Isolation Guarantees

```
Tenant A (schema s_456):
  ├─ Can only see data in s_456
  │   └─ User ID, Role ID, Invoice ID within s_456
  │
  ├─ Cannot access Tenant B's s_999:
  │   ├─ Even if SQL says SELECT s_999.invoices
  │   ├─ Hibernate forces routing to s_456
  │   └─ Result: 0 rows (s_456 has different invoice IDs)
  │
  └─ Cannot access public schema data:
      ├─ Except through allowed public views
      ├─ e.g., Tenant table (read-only)
      └─ Never user-modifiable data
```

---

## Layer 3: SQL Query Safeguards

### Parameterized Queries

**NEVER:**
```java
// ❌ DANGEROUS: SQL injection vulnerable
String query = "SELECT * FROM invoices WHERE id = " + invoiceId;
entityManager.createNativeQuery(query).getResultList();
```

**ALWAYS:**
```java
// ✅ SAFE: Parameterized query
String query = "SELECT * FROM invoices WHERE id = ?1";
entityManager.createNativeQuery(query)
    .setParameter(1, invoiceId)
    .getResultList();

// ✅ Or use Spring Data JPA (auto-parameterized)
@Query("SELECT i FROM Invoice i WHERE i.id = :id")
Invoice findById(@Param("id") Long id);
```

### Spring Data JPA (Auto-Safe)

```java
// All Spring Data JPA queries are parameterized
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    
    // Generated SQL:
    // SELECT * FROM {schema}.invoices WHERE id = ?
    // Parameter binding is automatic
    Invoice findById(Long id);
    
    // Generated SQL:
    // SELECT * FROM {schema}.invoices WHERE customer_id = ? AND status = ?
    // Multiple parameters safely bound
    List<Invoice> findByCustomerIdAndStatus(Long customerId, InvoiceStatus status);
}
```

### Dangerous Pattern (Avoid)

```java
// ❌ BAD: String concatenation
String search = userInput;  // "' OR '1'='1"
String query = "SELECT * FROM invoices WHERE description LIKE '" + search + "'";

// ✅ GOOD: Parameterized
String query = "SELECT * FROM invoices WHERE description LIKE ?1";
entityManager.createNativeQuery(query)
    .setParameter(1, "%" + search + "%")
    .getResultList();
```

---

## Layer 4: Soft Deletion & Audit Trail

### Soft Delete Pattern

```java
// Entity definition
@Entity
@Table(name = "customers")
@SQLDelete(sql = "UPDATE customers SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Customer {
    
    @Id
    private Long id;
    
    private String name;
    
    @Column(nullable = false)
    private boolean deleted = false;
    
    @Column
    private Instant deletedAt;
}
```

**What happens on delete:**
```
customerRepository.delete(customer);

Generated SQL:
  UPDATE customers SET deleted = true, deleted_at = NOW()
  WHERE id = ? AND version = ?

NOT:
  DELETE FROM customers WHERE id = ?
```

**Benefits:**

| Benefit | Implementation |
|---------|-----------------|
| **Audit trail** | Timestamps show when deleted |
| **Recover deleted data** | WHERE deleted = false is filter, not hard delete |
| **Referential integrity** | Foreign keys don't break |
| **Report accuracy** | Historical data preserved |

### Audit Logging

```java
// Entity extends AuditableEntity
@Entity
@Table(name = "invoices")
public class Invoice extends AuditableEntity {
    
    @Id
    private Long id;
    
    private String invoiceNumber;
    
    // Inherited from AuditableEntity:
    private Instant createdAt;      // Auto-set on insert
    private Instant updatedAt;      // Auto-updated
    private Long createdBy;         // User ID who created
    private Long updatedBy;         // User ID who modified
}

// Automatic tracking
Invoice invoice = new Invoice(...);
invoiceRepository.save(invoice);

// Database row:
{
  "id": 1,
  "invoice_number": "INV-2026-0001",
  "created_at": "2026-05-14T14:30:00Z",
  "created_by": 123,              // ← User ID recorded
  "updated_at": "2026-05-14T14:30:00Z",
  "updated_by": 123
}

// User modifies invoice
invoice.setStatus(PAID);
invoiceRepository.save(invoice);

// Database row after update:
{
  ...
  "updated_at": "2026-05-14T15:45:00Z",
  "updated_by": 456               // ← Different user ID
}
```

### Dedicated Audit Log Table

```sql
CREATE TABLE IF NOT EXISTS audit_logs (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT,
  entity_type VARCHAR(255),
  entity_id BIGINT,
  action VARCHAR(50),             -- CREATE, UPDATE, DELETE
  old_values JSONB,
  new_values JSONB,
  created_at TIMESTAMP DEFAULT NOW(),
  created_by BIGINT
);

Example audit entry:
{
  "id": 1001,
  "tenant_id": 456,               -- ← Tenant isolation
  "user_id": 123,
  "entity_type": "Invoice",
  "entity_id": 1,
  "action": "UPDATE",
  "old_values": { "status": "OPEN" },
  "new_values": { "status": "PAID" },
  "created_at": "2026-05-14T15:45:00Z",
  "created_by": 123
}
```

---

## Layer 5: Permission Validation

### Multi-Level Permission Checks

```
Request: GET /api/v1/invoices/999

1. Authentication Filter:
   ├─ Validate JWT signature ✓
   ├─ Extract userId=123, tenantId=456 ✓
   ├─ Set TenantContext(456) ✓
   └─ Load permissions from cache ✓

2. Authorization Layer:
   ├─ @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
   ├─ Check SecurityContext.authorities
   ├─ User has BILLING_MANAGE? ✓
   └─ Proceed

3. Repository Query:
   ├─ Repository.findById(999)
   ├─ Generated query:
   │   SELECT * FROM s_456.invoices WHERE id = ?1
   │   └─ Note: Using s_456 (Tenant A's schema)
   │
   ├─ If invoice 999 exists in s_456: Return ✓
   ├─ If invoice 999 doesn't exist in s_456:
   │   └─ Return null → 404 NOT FOUND
   │
   └─ (Cannot access s_999.invoices.999 via schema routing)

4. Response:
   └─ Invoice data from s_456 only
```

### Permission Cache

```
Cache key: "perms:s_456:123"
Cache value: ["BILLING_MANAGE", "CUSTOMER_MANAGE"]

User changes permissions:
  ├─ Admin modifies role
  ├─ Permissions change
  └─ Cache invalidation trigger:
      ├─ Key: "perms:s_456:123"
      ├─ Action: DELETE
      └─ User's next request fetches new permissions from DB

Result: Permission changes are effective within seconds
```

---

## Layer 6: Data Isolation Verification

### Testing Pattern

```java
@Test
public void testCrossTenantIsolation() {
    // Setup: Create invoices in different tenants
    Invoice invoiceA = createInvoice(tenantA, "INV-A-001");
    Invoice invoiceB = createInvoice(tenantB, "INV-B-001");
    
    // Query as Tenant A
    TenantContext.setTenantId(tenantA.getId());
    TenantContext.setCurrentSchema(tenantA.getSchemaName());
    
    Invoice result = invoiceRepository.findById(invoiceB.getId());
    
    // Assertion: Tenant A should NOT see Tenant B's invoice
    assertThat(result).isNull();
    
    // Verify: Tenant B's invoice still exists
    TenantContext.setTenantId(tenantB.getId());
    TenantContext.setCurrentSchema(tenantB.getSchemaName());
    
    result = invoiceRepository.findById(invoiceB.getId());
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(invoiceB.getId());
}
```

### Data Breach Simulation

```java
@Test
public void testSQLInjectionPrevention() {
    // Attacker tries: userId = "123' OR '1'='1"
    String maliciousInput = "123' OR '1'='1";
    
    // Our code uses parameterized query:
    String query = "SELECT u FROM User u WHERE id = :id";
    TypedQuery<User> typedQuery = entityManager.createQuery(query, User.class);
    typedQuery.setParameter("id", maliciousInput);
    
    // Result: Parameterization prevents injection
    // Database sees literal string, not SQL logic
    assertThat(typedQuery.getResultList()).isEmpty();
}
```

---

## Layer 7: Async Task Safety

### Problem: Async Tasks Lose TenantContext

```java
// ❌ WRONG: TenantContext lost in async thread
@Service
public class InvoiceService {
    @Async
    public void generateInvoicePdf(Long invoiceId) {
        // TenantContext.getTenantId() == null ← LOST!
        
        Invoice invoice = invoiceRepository.findById(invoiceId);
        // This query uses default schema (public)
        // → Will fail to find invoice
    }
}

// Usage:
TenantContext.setTenantId(456);
invoiceService.generateInvoicePdf(1);
// Async task runs in different thread
// → TenantContext is empty in that thread
```

### Solution: Thread-Aware Async

```java
// ✅ CORRECT: Propagate TenantContext to async thread
@Service
public class InvoiceService {
    
    @Async
    public void generateInvoicePdf(Long invoiceId, Long tenantId) {
        try {
            // 1. Restore TenantContext in async thread
            TenantContext.setTenantId(tenantId);
            
            String schemaName = schemaCacheService.resolveSchemaName(tenantId);
            TenantContext.setCurrentSchema(schemaName);
            
            // 2. Now queries use correct schema
            Invoice invoice = invoiceRepository.findById(invoiceId);
            
            // 3. Generate PDF
            byte[] pdf = invoicePdfService.generate(invoice);
            
        } finally {
            // 4. CRITICAL: Clear TenantContext
            TenantContext.clear();
        }
    }
}

// Usage:
TenantContext.setTenantId(456);
invoiceService.generateInvoicePdf(1, 456);  // Pass tenantId explicitly
```

### Scheduled Tasks

```java
@Component
@RequiredArgsConstructor
public class BillingCycleJob {
    
    @Scheduled(cron = "0 0 0 * * *")  // Midnight daily
    public void processBillingCycles() {
        // Get all tenants
        List<Tenant> allTenants = tenantRepository.findAll();
        
        for (Tenant tenant : allTenants) {
            try {
                // Set context for this tenant
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());
                
                // Process billing for this tenant
                billingService.generateInvoices();
                
            } finally {
                // Clear context after processing each tenant
                TenantContext.clear();
            }
        }
    }
}
```

---

## Checklist: Security Review

### For Every New Endpoint

```
□ Authentication
  □ @PreAuthorize present
  □ JWT validation required
  □ TenantId extracted from JWT

□ TenantContext
  □ Set in filter (done by JwtAuthenticationFilter)
  □ Cleared in finally block
  □ Thread-local verified in tests

□ Authorization
  □ Permission check present
  □ Role validated from cache or DB

□ Database Queries
  □ Spring Data JPA used (auto-parameterized)
  □ OR parameterized native query
  □ NO string concatenation in queries
  □ WHERE clauses include tenant filtering
  □ Schema isolation via TenantContext

□ Soft Deletion
  □ Entity marked with @SQLDelete and @SQLRestriction
  □ Audit fields (createdBy, updatedBy) present
  □ Deleted records are never shown to users

□ Testing
  □ Cross-tenant query returns empty result
  □ SQL injection test passed
  □ Async task has correct TenantContext
  □ Audit trail recorded correctly

□ Documentation
  □ Permission level documented
  □ Data sensitivity documented
  □ Tenant isolation assumption noted
```

---

## Common Vulnerabilities & Fixes

### Vulnerability #1: Missing TenantContext.clear()

```java
// ❌ VULNERABLE
@Component
public class UnsafeFilter extends OncePerRequestFilter {
    protected void doFilterInternal(...) throws ServletException, IOException {
        // Set tenant
        TenantContext.setTenantId(tenantId);
        
        // Process request
        filterChain.doFilter(request, response);
        
        // FORGOT TO CLEAR → Thread reused → Data leak
    }
}

// ✅ FIXED
@Component
public class SafeFilter extends OncePerRequestFilter {
    protected void doFilterInternal(...) throws ServletException, IOException {
        try {
            TenantContext.setTenantId(tenantId);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();  // ← ESSENTIAL
        }
    }
}
```

### Vulnerability #2: Missing Schema Isolation Verification

```java
// ❌ VULNERABLE: Assumes Spring Data JPA handles schema routing
public Invoice getInvoice(Long invoiceId) {
    return invoiceRepository.findById(invoiceId).orElse(null);
    // If TenantContext not set → Uses default schema → Wrong data
}

// ✅ FIXED: Explicitly verify tenant context
public Invoice getInvoice(Long invoiceId) {
    Long currentTenant = TenantContext.getTenantId();
    if (currentTenant == null) {
        throw new SecurityException("TenantContext not set");
    }
    
    Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
    
    // Double-check: Verify invoice belongs to current tenant
    if (invoice != null && !invoice.getTenantId().equals(currentTenant)) {
        throw new AccessDeniedException("Invoice not in current tenant");
    }
    
    return invoice;
}
```

### Vulnerability #3: String Concatenation in Queries

```java
// ❌ VULNERABLE: SQL injection
public List<Invoice> search(String customerName) {
    String query = "SELECT * FROM invoices WHERE customer_name LIKE '" + customerName + "'";
    return entityManager.createNativeQuery(query, Invoice.class).getResultList();
}

// ✅ FIXED: Parameterized query
public List<Invoice> search(String customerName) {
    String query = "SELECT * FROM invoices WHERE customer_name LIKE :name";
    return entityManager.createNativeQuery(query, Invoice.class)
        .setParameter("name", "%" + customerName + "%")
        .getResultList();
}

// ✅ BEST: Spring Data JPA (auto-safe)
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByCustomerNameLike(String customerName);
}
```

---

## Security Incident Response

### Data Breach Detection

```
Alert Triggers:
  ├─ TOKEN_REVOKED (401) from same user multiple times
  │   └─ Possible token compromise
  │
  ├─ QueryEngine: Schema mismatch in query routing
  │   └─ Possible misconfiguration
  │
  └─ AuditLog: Unusual access pattern
      ├─ User A accessing User B's resources
      ├─ Successful cross-tenant query
      └─ Alert: Possible security breach

Response:
  1. Immediate: Revoke all user tokens
  2. Investigation: Check audit logs for what data accessed
  3. Notification: Inform affected customer
  4. Remediation: Patch vulnerable code
  5. Verification: Security review of isolation layers
```

---

## Summary & Key Takeaways

| Layer | Mechanism | Failure Impact |
|-------|-----------|----------------|
| **JWT Validation** | Signature + expiry check | Forged tokens / Old compromised tokens |
| **TenantContext** | Thread-local + finally clear | Data leak to next request (same thread) |
| **Schema Isolation** | Database schema routing | Query returns wrong tenant's data |
| **Parameterized Queries** | Bind variables, not concatenation | SQL injection bypasses all checks |
| **Soft Deletion** | Flag-based, not hard delete | Accidentally return deleted data |
| **Audit Logging** | Track all changes | Cannot detect/investigate breaches |
| **Permission Cache** | Redis with invalidation | Stale permissions allow unauthorized access |
| **Async Tasks** | Explicit TenantContext propagation | Queries use wrong schema in background jobs |

**CRITICAL RULE:** Any one of these layers failing can result in data breach. All must be properly implemented and tested.

