---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - security
  - authorization
  - rbac
  - permissions
  - roles
  - spring-security
  - access-control
related_documents:
  - ./authentication.md
  - ../02-multi-tenancy/multi-tenancy-strategy.md
---

# Authorization & RBAC

## Executive Summary

**Authorization** is the mechanism that determines what authenticated users are allowed to do. MTBS uses **Role-Based Access Control (RBAC)**: each user has one role, each role has many permissions, and each endpoint requires specific permissions (e.g., `@PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")`). Roles and permissions are stored in the public schema (shared across all tenants), but permissions are cached per-tenant per-user in Redis for performance. There are 3 **system roles** (OWNER, ADMIN, EMPLOYEE) that cannot be modified, plus custom roles can be created by OWNER. Without proper authorization, any authenticated user could access billing data, delete users, or change settings—defeating multitenancy isolation. This document explains the RBAC model, the permission caching strategy, and common permission patterns.

---

## Context / Problem

### Why RBAC Instead of Other Models?

Three authorization models were considered:

1. **Attribute-Based Access Control (ABAC)** — Access decisions based on attributes (user.department=="billing", resource.owner==user.id, etc.)
   - ✅ Highly flexible, can encode complex business rules
   - ❌ Complex to implement, hard to reason about, difficult to audit
   - ❌ Performance issues: must evaluate attributes on every request

2. **Access Control Lists (ACL)** — Each resource has a list of who can access it
   - ✅ Fine-grained per-resource control
   - ❌ Scaling issue: 1,000 resources × 100 users = 100K ACL entries
   - ❌ Doesn't scale to multi-tenant with thousands of resources per tenant

3. **Role-Based (RBAC)** (CHOSEN) — Users → Roles → Permissions
   - ✅ Simple to reason about: "What role is this user?"
   - ✅ Scales efficiently: 10 roles × 20 permissions = 200 entries regardless of user count
   - ✅ Easy to audit: clear permission matrix
   - ✅ Easy to create custom roles for new departments
   - ✅ Spring Security native support

### Why Cache Permissions in Redis?

**Without caching**, every request to `/api/invoices` would:
```
GET JWT → extract userId, roleId
Query: SELECT permissions FROM role_permissions 
       WHERE role_id = ? [database hit]
Query: SELECT * FROM permissions WHERE id IN (...) [database hit]
→ 2 database queries per request
```

With 1,000 concurrent users, 2,000 queries/second to database = database bottleneck.

**With Redis caching** (TTL 15 min):
```
GET JWT → extract userId, roleId
Check Redis: GET perms:s_456:userId:123 [cache hit, <1ms]
Return cached permission set
→ No database queries if cache hit
```

Cache key format: `perms:{schemaName}:{userId}`
- Scoped per-tenant (schemaName ensures no cross-tenant permission bleed)
- Scoped per-user (different users have different permissions)
- TTL 15 min (balance between freshness and cache hit rate)

### Why System Roles (OWNER, ADMIN, EMPLOYEE)?

Every tenant needs basic roles out-of-the-box:

| Role | Purpose | Permissions | Created By |
|------|---------|-------------|-----------|
| OWNER | Founder of tenant org | All billing, user, role, customer, product management | SignupService during signup |
| ADMIN | Senior employee | All except account/billing settings | OWNER (manual invite) |
| EMPLOYEE | Junior employee | View and create business data (invoices, customers, products) | OWNER (manual invite) |

System roles cannot be deleted or modified (prevent accidental lockout). Custom roles can be created for specific needs (e.g., "Accountant" with only PERMISSION_BILLING_MANAGE + PERMISSION_BILLING_VIEW).

---

## Dependencies

### Inbound (Who Checks Authorization)
- `JwtAuthenticationFilter` → Extract userId, roleId from JWT claim → Build UserPrincipal with authorities
- Spring Security's `@PreAuthorize` annotation → Check if user's authorities include required permission
- `GlobalExceptionHandler` → Catch `AccessDeniedException` → Return 403 Forbidden

### Outbound (Authorization Depends On)
- `RoleService` → Fetch roles, assign/remove permissions
- `PermissionService` → Fetch all available permissions (admin page)
- `PermissionCacheService` → Cache permissions in Redis
- `PermissionRepository` → Query permissions from public schema
- `RolePermissionRepository` → Query role-permission assignments from public schema
- `UserRepository` → Query users by role to prevent role deletion with assigned users

### Configuration
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` — JWT issuer (if using external provider)
- `security.jwt.signing-key` — Secret for JWT signature verification
- `spring.redis.host`, `spring.redis.port` — Redis for permission caching
- `security.authorization.cache-ttl: 15m` — Permission cache expiration (default 15 minutes)

---

## Design / Implementation

### Permission Model

```java
@Entity
@Table(name = "permissions", schema = "public")
public class Permission extends AuditableEntity {
    
    @Column(nullable = false, unique = true)
    private String name;  // "PERMISSION_BILLING_MANAGE"
    
    @Column
    private String description;  // "Can manage subscriptions, invoices, payments"
}
```

**Permissions Defined** (Platform Level):

```
TENANT OPERATIONS:
  PERMISSION_TENANT_VIEW         → View tenant profile, billing summary
  PERMISSION_TENANT_MANAGE       → Update tenant name, logo, settings

USER MANAGEMENT:
  PERMISSION_USER_VIEW           → List users, view user details
  PERMISSION_USER_MANAGE         → Create users, update roles, change status
  PERMISSION_USER_DELETE         → Delete users

ROLE MANAGEMENT:
  PERMISSION_ROLE_VIEW           → List roles, view role permissions
  PERMISSION_ROLE_MANAGE         → Create/update/delete custom roles, assign permissions

BILLING (PLATFORM):
  PERMISSION_BILLING_MANAGE      → View invoices, initiate upgrades, process payments
  PERMISSION_BILLING_VIEW        → View invoices, subscriptions (read-only)

BUSINESS OPERATIONS:
  PERMISSION_CUSTOMER_MANAGE     → Create/update/delete customers
  PERMISSION_CUSTOMER_VIEW       → List/view customers (read-only)
  PERMISSION_PRODUCT_MANAGE      → Create/update/delete products
  PERMISSION_PRODUCT_VIEW        → List/view products (read-only)
```

### Role Entity

```java
@Entity
@Table(name = "roles")
public class Role extends AuditableEntity {
    
    @Column(nullable = false, unique = true)
    private String name;  // "OWNER", "ADMIN", "EMPLOYEE", or custom "Accountant"
}
```

**System Roles** (Immutable):

| Role | Can Create Users | Can Manage Billing | Can Delete Users | Can Delete Roles |
|------|-----------------|-------------------|-----------------|------------------|
| OWNER | ✅ | ✅ | ✅ | ✅ (custom only) |
| ADMIN | ✅ | ✅ | ✅ | ❌ |
| EMPLOYEE | ❌ | ❌ | ❌ | ❌ |

### RolePermission Join Table

```java
@Entity
@Table(name = "role_permissions")
public class RolePermission extends AuditableEntity {
    
    @Column(name = "role_id", nullable = false)
    private Long roleId;
    
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;
    
    @ManyToOne
    @JoinColumn(name = "role_id", insertable = false, updatable = false)
    private Role role;
    
    @ManyToOne
    @JoinColumn(name = "permission_id", insertable = false, updatable = false)
    private Permission permission;
}
```

**Example**: OWNER role has these RolePermission associations:

```
RolePermission { roleId=1, permissionId=1 }  → PERMISSION_TENANT_MANAGE
RolePermission { roleId=1, permissionId=2 }  → PERMISSION_USER_MANAGE
RolePermission { roleId=1, permissionId=3 }  → PERMISSION_USER_DELETE
RolePermission { roleId=1, permissionId=4 }  → PERMISSION_ROLE_MANAGE
RolePermission { roleId=1, permissionId=5 }  → PERMISSION_BILLING_MANAGE
RolePermission { roleId=1, permissionId=6 }  → PERMISSION_CUSTOMER_MANAGE
RolePermission { roleId=1, permissionId=7 }  → PERMISSION_PRODUCT_MANAGE
```

### Authorization Flow (Per Request)

```
1. HTTP request arrives with JWT in cookie
   ↓
2. JwtAuthenticationFilter.doFilterInternal()
   ├─ Extract JWT → validate signature
   ├─ Extract claims: userId, tenantId, roleId
   ├─ Call PermissionCacheService.getPermissions(schemaName, userId, roleId)
   │   └─ Check Redis cache for "perms:{schema}:{userId}"
   │       ├─ Cache HIT: return cached permission set
   │       └─ Cache MISS:
   │           ├─ Query rolePermissionRepository for roleId
   │           ├─ Extract permission names
   │           ├─ Store in Redis with TTL=15min
   │           └─ Return permission set
   │
   ├─ Build UserPrincipal with authorities = permission set
   │   UserPrincipal {
   │     userId: 123,
   │     tenantId: 456,
   │     roleId: 1,
   │     authorities: ["PERMISSION_BILLING_MANAGE", "PERMISSION_USER_MANAGE", ...]
   │   }
   │
   ├─ Set SecurityContext.setAuthentication(authentication)
   └─ Continue to endpoint
   ↓
3. SubscriptionController.listInvoices()
   ├─ @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
   ├─ Spring Security checks: does userPrincipal.authorities contain "PERMISSION_BILLING_MANAGE"?
   │   ├─ YES: endpoint executes, fetches invoices
   │   └─ NO: throw AccessDeniedException → GlobalExceptionHandler → HTTP 403 Forbidden
   └─ Return response
```

### Permission Caching (PermissionCacheService)

```java
public Set<String> getPermissions(String schemaName, Long userId, Long roleId) {
    // Build cache key: "perms:{schemaName}:{userId}"
    String key = "perms:" + schemaName + ":" + userId;
    
    // Try Redis first
    String cached = redis.get(key);
    if (cached != null && !cached.isBlank()) {
        return parsePermissions(cached);  // e.g., "PERM_A,PERM_B,PERM_C"
    }
    
    // Cache miss — query database
    List<RolePermission> rolePerms = db.findByRoleId(roleId);
    Set<String> permNames = rolePerms.stream()
        .map(rp -> rp.getPermission().getName())
        .collect(toSet());
    
    // Store in Redis with TTL=15 minutes
    String value = String.join(",", permNames);
    redis.set(key, value, Duration.ofMinutes(15));
    
    return permNames;
}
```

**Cache invalidation**:
- When role permissions change: `PermissionCacheService.evictUser(schemaName, userId)`
- When all tenant permissions need refresh: `PermissionCacheService.evictTenant(schemaName)`

### API Endpoints for Role/Permission Management

#### Get All Roles
`GET /api/v1/roles` (requires `PERMISSION_ROLE_VIEW`)

```java
@GetMapping
@PreAuthorize("hasAuthority('PERMISSION_ROLE_VIEW')")
public ResponseEntity<ApiResponse<List<RoleResponse>>> getAllRoles()
```

Response:
```json
{
  "status": "SUCCESS",
  "data": [
    { "id": 1, "name": "OWNER", "permissionCount": 7 },
    { "id": 2, "name": "ADMIN", "permissionCount": 6 },
    { "id": 3, "name": "EMPLOYEE", "permissionCount": 3 }
  ]
}
```

#### Create Custom Role
`POST /api/v1/roles` (requires `PERMISSION_ROLE_MANAGE`)

```java
@PostMapping
@PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
public ResponseEntity<ApiResponse<RoleResponse>> createRole(@RequestBody CreateRoleRequest request)
```

Request:
```json
{
  "name": "Accountant"
}
```

Response:
```json
{
  "id": 10,
  "name": "Accountant",
  "permissionCount": 0
}
```

#### Assign Permission to Role
`POST /api/v1/roles/{id}/permissions` (requires `PERMISSION_ROLE_MANAGE`)

```java
@PostMapping("/{id}/permissions")
@PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
public ResponseEntity<ApiResponse<RoleDetailResponse>> assignPermissionToRole(
    @PathVariable Long id,
    @RequestBody AssignPermissionRequest request)
```

Request:
```json
{
  "permissionId": 5
}
```

Response:
```json
{
  "id": 10,
  "name": "Accountant",
  "permissions": [
    { "id": 5, "name": "PERMISSION_BILLING_MANAGE", "description": "..." },
    { "id": 6, "name": "PERMISSION_BILLING_VIEW", "description": "..." }
  ]
}
```

#### Remove Permission from Role
`DELETE /api/v1/roles/{id}/permissions/{permId}` (requires `PERMISSION_ROLE_MANAGE`)

```java
@DeleteMapping("/{id}/permissions/{permId}")
@PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
public ResponseEntity<ApiResponse<Void>> removePermissionFromRole(
    @PathVariable Long id,
    @PathVariable Long permId)
```

Response: `204 No Content`

---

## Flow

### Authorization Decision Flow

```
┌──────────────────────────────────────────────┐
│ User logs in                                 │
│ POST /api/auth/login { email, password }    │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ AuthService.login()                          │
│ • Validate credentials                       │
│ • Fetch user + role from database            │
│ • Extract roleId                             │
│ • Generate JWT with claim: roleId=1          │
│ • Send JWT in HttpOnly cookie                │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ User makes request: GET /api/invoices        │
│ Cookie: _jwt=eyJhbGc...                      │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ JwtAuthenticationFilter.doFilterInternal()   │
│ • Extract JWT from cookie                    │
│ • Decode JWT → get userId=123, roleId=1     │
│ • Call PermissionCacheService                │
│   .getPermissions(schemaName, userId,       │
│     roleId)                                  │
└────────────┬─────────────────────────────────┘
             │
             ▼
    ┌────────────────────┐
    │ Check Redis cache  │
    │ "perms:s_456:123"  │
    └─┬──────────────┬───┘
      │ HIT          │ MISS
      ▼              ▼
   ┌───┐    ┌────────────────────────┐
   │   │    │ Query database:        │
   │   │    │ SELECT rp FROM         │
   │   │    │   role_permissions rp  │
   │   │    │ WHERE rp.role_id = 1   │
   │   │    │  JOIN permission p     │
   │   │    │ SELECT permission.name │
   │   │    └────────────┬───────────┘
   │   │                 │
   │   │                 ▼
   │   │    ┌────────────────────────┐
   │   │    │ Cache in Redis:        │
   │   │    │ SET perms:s_456:123    │
   │   │    │ "PERM_A,PERM_B,..."    │
   │   │    │ EX 900 (15 min)        │
   │   │    └────────────┬───────────┘
   │   │                 │
   └───┴─────────────────┘
       │
       ▼
┌──────────────────────────────────────┐
│ UserPrincipal created with           │
│ authorities = {                      │
│   "PERMISSION_BILLING_MANAGE",       │
│   "PERMISSION_USER_MANAGE",          │
│   "PERMISSION_ROLE_MANAGE",          │
│   ...                                │
│ }                                    │
└────────────┬────────────────────────┘
             │
             ▼
┌──────────────────────────────────────┐
│ Request reaches controller:          │
│ @PreAuthorize(                       │
│   "hasAuthority(                     │
│    'PERMISSION_BILLING_MANAGE')"     │
│ )                                    │
│ public listInvoices() { ... }        │
└────────────┬────────────────────────┘
             │
    ┌────────┴──────────────────┐
    │                           │
    ▼ (Has Permission)          ▼ (No Permission)
┌──────────────────┐    ┌─────────────────────┐
│ Endpoint         │    │ Spring Security     │
│ executes         │    │ throws              │
│ Fetch invoices   │    │ AccessDeniedException
│ Return 200 OK    │    │                     │
└──────────────────┘    └────────────┬────────┘
                                     │
                                     ▼
                        ┌────────────────────────┐
                        │ GlobalExceptionHandler │
                        │ Catches               │
                        │ AccessDeniedException │
                        │ Returns:              │
                        │ HTTP 403 Forbidden    │
                        │ {                     │
                        │   "status": "ERROR",  │
                        │   "message":          │
                        │   "Access Denied"     │
                        │ }                     │
                        └────────────────────────┘
```

---

## Code References

| Class | Tag | Method/Purpose | Role |
|-------|-----|----------------|------|
| `Role` | [AUTH-2] | Entity mapping | Represents a role (OWNER, ADMIN, EMPLOYEE, custom) |
| `Permission` | [AUTH-3] | Entity mapping | Represents a permission (PERMISSION_BILLING_MANAGE, etc.) |
| `RolePermission` | [AUTH-4] | Join table mapping | Associates roles with permissions (many-to-many) |
| `RoleService` | [AUTH-19] | `getAllRoles()` | Fetch all roles for tenant |
| `RoleService` | [AUTH-19] | `createRole()` | Create custom role |
| `RoleService` | [AUTH-19] | `assignPermissionToRole()` | Add permission to role |
| `RoleService` | [AUTH-19] | `removePermissionFromRole()` | Remove permission from role |
| `PermissionService` | [AUTH-20] | `getAllPermissions()` | Fetch all available permissions (for UI) |
| `PermissionCacheService` | [AUTH-28] | `getPermissions()` | Get cached or fetch permissions for user |
| `PermissionCacheService` | [AUTH-28] | `evictUser()` | Clear cache when user role changes |
| `RoleController` | [AUTH-40] | GET `/roles` | List all roles |
| `RoleController` | [AUTH-40] | POST `/roles` | Create custom role |
| `RoleController` | [AUTH-40] | POST `/roles/{id}/permissions` | Assign permission to role |
| `PermissionController` | [AUTH-41] | GET `/permissions` | List all permissions (admin UI) |

---

## Rules / Constraints

1. **System roles (OWNER, ADMIN, EMPLOYEE) MUST NOT be modified or deleted** — These roles are the foundation of tenant access control. If OWNER role is accidentally deleted, all OWNER users lose access to settings. Check: `if (SYSTEM_ROLES.contains(role.getName())) throw AccessDeniedException`.

2. **Permission cache MUST be invalidated when role permissions change** — If role permissions are updated but cache not invalidated, user continues using old permissions until cache expires (15 min). On every `assignPermissionToRole()` or `removePermissionFromRole()`, call `PermissionCacheService.evictTenant(schemaName)` to flush all cached permissions for the tenant.

3. **role_id MUST be extracted from JWT, not from request parameter** — A malicious user could send `GET /api/invoices?roleId=999` (admin role) and if roleId read from parameter, would bypass authorization. ALWAYS extract roleId from JWT claim (which is signed by server), never from user input.

4. **@PreAuthorize annotation MUST be explicit about required permissions** — Do not use generic checks like `hasRole('OWNER')` (Spring's old syntax). Use `hasAuthority('PERMISSION_X')` (modern syntax) to be explicit about which permission is needed. Makes permission matrix auditable.

5. **Permission names MUST be unique across the platform** — If two permissions have the same name (e.g., "BILLING_MANAGE" in two different modules), cache key collision causes wrong permissions loaded. Use naming convention: `PERMISSION_{DOMAIN}_{ACTION}` (e.g., `PERMISSION_BILLING_MANAGE`, `PERMISSION_CUSTOMER_DELETE`).

6. **Role-permission assignment MUST be idempotent** — If same permission assigned to same role twice (accidental double-submit), second attempt should fail gracefully with "already exists" error, not corrupt database. Check: `if (existsByRoleIdAndPermissionId(...)) throw AlreadyExistsException`.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| User tries to delete OWNER role | `AccessDeniedException` (caught by check) | 400 Bad Request | UI should hide delete button for system roles |
| User tries to modify ADMIN role permissions | `AccessDeniedException` (in RoleService.updateRole) | 400 Bad Request | System roles cannot be edited; UI shows read-only |
| User without PERMISSION_BILLING_MANAGE tries to create invoice | `AccessDeniedException` from @PreAuthorize | 403 Forbidden | User redirected to permission error page |
| Redis cache service down, permission lookup fails | `RedisConnectionException` | 503 Service Unavailable | Fallback: load permissions from database (slower) without cache |
| Permission cache corrupted (wrong schema in key) | User gets wrong permissions from cache | 403 Forbidden (incorrect denial) OR 200 OK (incorrect grant) | Manual cache flush; restart Redis; check TenantContext.getCurrentSchema() is correct |
| Assign permission to role but commit fails | `DataIntegrityViolationException` mid-transaction | 500 Internal Server Error | Transaction rolled back; permission not assigned; user retains old permissions; retry endpoint |
| Two concurrent requests modify same role permissions | `OptimisticLockException` (version mismatch on Role entity) | 409 Conflict | Retry request; Hibernate @Version prevents lost updates |
| Role has assigned users and deletion attempted | `AccessDeniedException` (check finds users) | 400 Bad Request | Role cannot be deleted while users assigned; reassign users first |

---

## Edge Cases

- **Concurrency**: Two simultaneous requests assign and remove same permission from role. Database row-level lock + optimistic locking prevents inconsistent state. Second request gets OptimisticLockException and must retry.

- **Timezone**: Role and permission entities don't use timestamps for authorization decisions. Audit trail timestamps (created_at, updated_at) are UTC. No timezone confusion.

- **Tenant Isolation**: Permission cache key includes schemaName (`perms:{schemaName}:{userId}`). User from tenant A cannot access tenant B's cache even if they somehow know tenant B's userId (different schema key).

- **Empty State**: New tenant has no custom roles (only OWNER auto-created by signup). Querying `GET /api/roles` returns [OWNER, ADMIN, EMPLOYEE] with 0 custom roles. Creating first custom role works fine.

- **Permission Hierarchy**: No hierarchical permissions (e.g., ADMIN contains USER_MANAGE). Permissions are flat. If ADMIN should have all permissions, explicitly assign all of them in seed data (one-time setup).

- **Expired Session**: User's JWT expires, request fails authentication before authorization even checked. JwtAuthenticationFilter validates expiry, returns 401, user redirected to login.

---

## Known Issues / Limitations

1. **No permission audit trail** — When permissions are assigned/removed, no audit log entry created. For compliance, need to add audit logging to `RoleService.assignPermissionToRole()` and `.removePermissionFromRole()`.

2. **No time-based permission expiration** — Permissions are static. If a user should have BILLING_MANAGE only for Q1 2026, no mechanism to auto-revoke on Q2 start. Manual role change required or custom job needed.

3. **No permission delegation** — User with PERMISSION_ROLE_MANAGE cannot delegate that permission to another user temporarily. All-or-nothing assignment model.

4. **No per-resource permissions** — Authorization is global per-permission. Cannot grant "BILLING_MANAGE for invoices over ₹10,000 only" (would require ABAC). Current model: either user can manage all invoices or none.

5. **Cache TTL is fixed** — Permission cache always 15 minutes. Cannot configure per-tenant (some tenants might need 5 min for compliance, others 1 hour for performance).

6. **No role hierarchy** — Cannot define "ADMIN inherits from EMPLOYEE". If ADMIN needs all EMPLOYEE permissions, must explicitly assign all permissions. Simplicity trade-off.

---

## Future Improvements

1. Implement permission audit logging — Log who assigned/removed which permission when. Enable compliance audits and incident investigations.

2. Add time-based permission grants — Permission valid from X date to Y date. Auto-expiration without manual action. Useful for contractors, seasonal staff, temporary access.

3. Implement permission delegation — User with X permission can grant it to another user temporarily (e.g., "delegate my billing approval to colleague while I'm on vacation").

4. Add per-resource authorization — Extend authorization to be per-resource (invoice, customer, etc.). Implement @PostAuthorize to filter query results by resource ownership.

5. Add configurable cache TTL per-tenant — Tenants needing high compliance can set cache TTL to 5 min; tenants not needing this can set 1 hour for performance.

6. Implement role hierarchies — Define ADMIN role as inheriting from EMPLOYEE. Reduce permission assignment boilerplate. Transitive closure computed on assign.

---

## Related Documents
- [authentication.md](../03-security/authentication.md) — JWT token generation and validation (credential foundation for authorization)
- [jwt-token-lifecycle.md](../03-security/jwt-token-lifecycle.md) — Related Phase 3 document on JWT claims including roleId
- [tenant-context-lifecycle.md](../02-multi-tenancy/tenant-context-lifecycle.md) — TenantContext used in permission caching (schema name)
- [request-flow.md](../01-architecture/request-flow.md) — HTTP filter chain includes authorization filters
- [error-handling.md](../06-api/error-handling.md) — Related Phase 3 document on 403 Forbidden response
- [system-design.md](../01-architecture/system-design.md) — Architecture overview of Spring Security setup
