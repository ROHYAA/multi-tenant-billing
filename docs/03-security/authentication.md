---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - security
  - jwt
  - multitenancy
  - tokens
  - cookies
related_documents:
  - ./authorization-rbac.md
  - ../02-multi-tenancy/tenant-context-lifecycle.md
  - ../01-architecture/request-flow.md
---

# Authentication

## Executive Summary

MTBS uses **JWT-based stateless authentication** layered on a **schema-per-tenant multitenancy model**. Tenant users authenticate via email + password, receive `accessToken` + `refreshToken` as **HttpOnly, Secure, SameSite=Lax cookies** (not response body), and their JWT contains tenant context (`tenantId`, `schemaName`, `roleId`) retrieved from Redis cache on every request. Platform administrators receive separate `superAdminToken` stored in Redis. Without this system, API endpoints cannot determine tenant isolation boundaries or validate user permissions. The JWT filter is the first layer of defense against cross-tenant data leakage.

---

## Context / Problem

### Why Stateless Auth Over Sessions?

Stateless JWT allows horizontal scaling — every request carries its identity. With per-tenant PostgreSQL schemas, the JWT must encode `tenantId` so the filter knows which schema connection pool Hibernate consumes. Sessions would require distributed state (Redis clusters, sticky sessions) — JWT is simpler and audit-friendly.

### Why HttpOnly Cookies Over Response Body?

JWT tokens in response body are vulnerable to XSS attacks and accidental logging. HttpOnly cookies:
- Cannot be accessed by malicious JavaScript (`document.cookie` blocked)
- Auto-attached by browser on each request (transparent to frontend)
- Survive browser tab refreshes
- Explicitly marked `Secure` (HTTPS only) and `SameSite=Lax` (CSRF protection)

### Why Token Versioning?

When a user's password is reset or a role changes, existing tokens remain cryptographically valid but must be invalidated immediately. Token versioning (`tokenVersion` in JWT claim) paired with Redis invalidation allows logout-without-database-writes and instant permission revocation.

### Why Separate Admin Auth?

Platform admins (super admins) operate outside tenant contexts. A separate `superAdminToken` avoids confusion and prevents accidental tenant isolation bypass. Super admin JWT carries `isSuperAdmin: true` flag instead of `tenantId`.

---

## Dependencies

### Inbound (Who Calls This Module)
- `AuthController` → `AuthService.login()` — HTTP POST /api/auth/login
- `AuthController` → `SignupService.signup()` — HTTP POST /api/auth/signup
- `AuthController` → `RefreshTokenService.refresh()` — HTTP POST /api/auth/refresh
- `AuthController` → `PasswordResetService.reset()` — HTTP POST /api/auth/reset-password
- `JwtAuthenticationFilter` → `JwtTokenProvider.validateToken()` — Every HTTP request (filter chain)

### Outbound (What This Module Calls)
- `JwtAuthenticationFilter` → `TenantContext.setTenantId()` — Set tenant context ThreadLocal
- `JwtAuthenticationFilter` → `TenantContext.setCurrentSchema()` — Set schema context ThreadLocal
- `AuthService` → `TenantRepository.findById()` — Load tenant by ID from public schema
- `AuthService` → `SlugCacheService.resolveTenantId()` — Redis lookup: slug → tenantId
- `AuthService` → `SchemaCacheService.resolveSchemaName()` — Redis lookup: tenantId → schemaName
- `JwtAuthenticationFilter` → `TokenVersionCacheService.isTokenVersionValid()` — Redis lookup: validate token version
- `JwtAuthenticationFilter` → `PermissionCacheService.getPermissions()` — Redis lookup: user permissions for this token
- `CookieUtils.addAuthCookies()` → Set HttpOnly cookies with tokens
- `CookieUtils.extractRefreshToken()` — Extract refresh token from cookie

### Configuration
- `jwt.secret` — HMAC secret key (base64-encoded, minimum 32 bytes for HS256)
- `jwt.expiration` — Access token TTL (ms) — typically 900000 (15 minutes)
- `jwt.refresh-expiration` — Refresh token TTL (ms) — typically 604800000 (7 days)
- `jwt.issuer` — JWT issuer claim (e.g., "mtbs.io")
- `jwt.audience` — JWT audience claim (e.g., "mtbs-web")
- `spring.security.cors.allowed-origins` — Allowed CORS origins for browser requests

---

## Design / Implementation

### JWT Token Structure

#### Access Token (sent in Authorization header OR HttpOnly cookie)
```
{
  "sub": "userId",                  // Subject: user ID as string
  "tenantId": 123,                  // Tenant ID (numeric)
  "roleId": 456,                    // User's role ID in this tenant
  "tokenVersion": 2,                // Invalidation marker (co-located with this user in DB)
  "typ": "ACCESS",                  // Token type
  "iss": "mtbs.io",                 // Issuer
  "aud": ["mtbs-web"],              // Audience
  "iat": 1704067200000,             // Issued at (ms)
  "exp": 1704068100000              // Expiration (ms)
}
```

Signed with HMAC-SHA256 using `jwt.secret`.

#### Super Admin Token
```
{
  "sub": "adminId",
  "email": "admin@mtbs.io",
  "isSuperAdmin": true,             // Flag: this is super admin, NOT tenant user
  "typ": "ACCESS",
  "iss": "mtbs.io",
  "aud": ["mtbs-web"],
  "iat": 1704067200000,
  "exp": 1704068100000
}
```

No `tenantId` claim — super admin bypasses tenant context.

### Authentication Flow: Tenant User Login

```
┌─────────────────────────────────────────────────────────────┐
│  POST /api/auth/login                                       │
│  { "tenantSlug": "acme", "email": "user@acme.com", ... }   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
            ┌──────────────────────────────────┐
            │  LoginRateLimiter.checkBlocked() │
            │  (Verify IP not rate-limited)    │
            └──────────────┬───────────────────┘
                           │
                           ▼
         ┌─────────────────────────────────────────────┐
         │  SlugCacheService.resolveTenantId()        │
         │  Redis: tenantSlug → tenantId              │
         │  (Cache miss → TenantRepository.findBySlug)│
         └──────────────┬──────────────────────────────┘
                        │
                        ▼
       ┌───────────────────────────────────────────┐
       │  Load full Tenant entity                  │
       │  Check: status != SUSPENDED/INACTIVE      │
       │  (PENDING_ONBOARDING is allowed)          │
       └──────────────┬────────────────────────────┘
                      │
                      ▼
   ┌──────────────────────────────────────────────┐
   │  SchemaCacheService.resolveSchemaName()      │
   │  Redis: tenantId → schema name (e.g., "s_1")│
   │  (Cache miss → TenantRepository read)        │
   └──────────────┬───────────────────────────────┘
                  │
                  ▼
       ┌──────────────────────────────────────────┐
       │  TenantContext.setTenantId(tenantId)     │
       │  TenantContext.setCurrentSchema(schema)  │
       │  (ThreadLocal set for this request)      │
       └──────────────┬───────────────────────────┘
                      │
                      ▼
        ┌─────────────────────────────────────────┐
        │  TenantAuthService.loginInTenantSchema()│
        │  (Executes in tenant's schema)          │
        │  1. Load User by email                  │
        │  2. Verify password (BCrypt)            │
        │  3. Check User.status == ACTIVE         │
        │  4. Generate JWT tokens                 │
        │  5. Store RefreshToken entity           │
        └──────────────┬──────────────────────────┘
                       │
                       ▼
    ┌────────────────────────────────────────────┐
    │  JwtTokenProvider.generateToken()          │
    │  Creates JWT payload + cryptographic sign  │
    │  Returns: accessToken (JWT string)         │
    └──────────────┬─────────────────────────────┘
                   │
                   ▼
  ┌─────────────────────────────────────────────┐
  │  CookieUtils.addAuthCookies()               │
  │  Set HttpOnly, Secure, SameSite=Lax cookies│
  │  Cookie: "accessToken" = JWT                │
  │  Cookie: "refreshToken" = tokenId           │
  └──────────────┬────────────────────────────────┘
                 │
                 ▼
          ┌─────────────────────────────┐
          │  TenantContext.clear()      │
          │  (finally block cleanup)    │
          └─────────────────────────────┘
                 │
                 ▼
    ┌────────────────────────────────────┐
    │  HTTP 200 OK                       │
    │  AuthResponse (no tokens in body)  │
    │  Set-Cookie headers included       │
    └────────────────────────────────────┘
```

### Request Filter: JWT Extraction & Validation

Every HTTP request flows through `JwtAuthenticationFilter`:

```
┌─────────────────────────────────────────┐
│  HTTP Request arrives                   │
│  GET /api/billing/invoices              │
└──────────────────┬──────────────────────┘
                   │
                   ▼
    ┌──────────────────────────────────┐
    │  JwtAuthenticationFilter.doFilter │
    │  (OncePerRequestFilter)           │
    └──────────────┬───────────────────┘
                   │
                   ▼
    ┌────────────────────────────────────┐
    │  getJwtFromRequest()                │
    │  Sources (priority):                │
    │  1. Authorization: Bearer <jwt>    │
    │  2. HttpOnly Cookie: accessToken   │
    │  3. Not found → doFilter() chain   │
    │     (Unauthenticated request)      │
    └──────────────┬─────────────────────┘
                   │
                   ▼
       ┌───────────────────────────────────┐
       │  JwtTokenProvider.validateToken() │
       │  Verify HMAC signature + expiry   │
       │  Invalid → doFilter() chain (401) │
       └──────────────┬────────────────────┘
                      │
                      ▼
      ┌────────────────────────────────────┐
      │  Parse JWT Claims                  │
      │  Check: isSuperAdmin flag?         │
      │  YES → Set SUPER_ADMIN authority   │
      │  NO  → Extract tenant context      │
      └──────────────┬─────────────────────┘
                     │
                (SUPER_ADMIN PATH)    (TENANT PATH)
                     │                     │
                     ▼                     ▼
        ┌──────────────────────  ┌────────────────────────┐
        │ UserPrincipal:         │ Extract JWTClaims:     │
        │ - id                   │ - userId               │
        │ - email                │ - tenantId             │
        │ - authorities: [       │ - roleId               │
        │  "ROLE_SUPER_ADMIN"]   │ - tokenVersion         │
        └──────────────────────  └────────────┬───────────┘
                  │                          │
                  │          ┌───────────────────────────────┐
                  │          │  SchemaCacheService.resolve() │
                  │          │  Lookup: tenantId → schema    │
                  │          └────────────┬──────────────────┘
                  │                       │
                  │          ┌────────────────────────────────┐
                  │          │  TenantContext.setTenantId()   │
                  │          │  TenantContext.setCurrentSchema│
                  │          │  (ThreadLocal set)             │
                  │          └────────────┬───────────────────┘
                  │                       │
                  │          ┌────────────────────────────────┐
                  │          │  Check: Token Version Valid?   │
                  │          │  Redis key: schema:tId:uId:v   │
                  │          │  If INVALID → 401 TOKEN_REVOKED│
                  │          └────────────┬───────────────────┘
                  │                       │
                  │          ┌────────────────────────────────┐
                  │          │  PermissionCacheService.get()  │
                  │          │  Lookup: user permissions      │
                  │          │  Build GrantedAuthority list   │
                  │          └────────────┬───────────────────┘
                  │                       │
                  └────────────┬──────────┘
                               │
                    ┌──────────────────────────────────┐
                    │  UserPrincipal ppl                │
                    │  - id, email, tenantId, roleId    │
                    │ - authorities: [perms from Redis] │
                    └──────────────┬───────────────────┘
                                   │
                    ┌──────────────────────────────────┐
                    │  SecurityContext.setAuthentication│
                    │  (Spring Security stores it)     │
                    └──────────────┬───────────────────┘
                                   │
                    ┌──────────────────────────────────┐
                    │  filterChain.doFilter()          │
                    │  (Proceed to endpoint)           │
                    │  Endpoint can call:              │
                    │  - @RequestHeader("Authorization")
                    │  - SecurityContextHolder.get()   │
                    └──────────────────────────────────┘
```

**Key Invariant**: TenantContext ThreadLocal is set **before** `filterChain.doFilter()` and **cleared in finally block** — this ensures `@Transactional` methods execute in the correct tenant schema, even on exception.

### Token Refresh Flow

When access token expires (15 minutes):

```
POST /api/auth/refresh
{
  "tenantSlug": "acme",
  "refreshToken": "tokenId"  (from cookie, mostly)
}
     │
     ▼
[Same tenant lookup + TenantContext setup as login]
     │
     ▼ TenantAuthService.refreshInTenantSchema()
     │
     ├─ Load RefreshToken entity by ID + secret hash
     ├─ Verify expiry (7 days default)
     ├─ Load linked User
     ├─ Generate NEW access + refresh tokens
     ├─ Rotate refresh token (optional, for security)
     └─ Set HttpOnly cookies
           │
           ▼
     HTTP 200 + Set-Cookie headers (NEW tokens)
```

No database write occurs for access token generation — only cryptographic signing. Refresh token validation is 1 DB lookup.

### User Principal & Permission Authorities

`UserPrincipal` is the Spring Security `UserDetails` for each authenticated principal:

```java
public class UserPrincipal implements UserDetails {
    private final Long id;              // Database user ID
    private final String email;         // Unique in tenant schema
    private final Long tenantId;        // Cross-check: should match TenantContext
    private final String schemaName;    // Redundant but cached here
    private final Long roleId;          // Role ID (not entity instance)
    private final String role;          // Role name (for logging)
    private final Collection<? extends GrantedAuthority> authorities;
    // authorities = List of SimpleGrantedAuthority from Redis
    // e.g., ["TENANT_VIEW", "CUSTOMER_MANAGE", "BILLING_MANAGE"]
}
```

These authorities are checked by `@PreAuthorize("hasAuthority('TENANT_VIEW')")` annotations on controller methods.

---

## Flow

See JWT Extraction & Validation ASCII art above (Request Filter section).

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `JwtTokenProvider` | [AUTH-29] | `generateToken(userId, tenantId, roleId, tokenVersion)` | Creates access JWT with tenant context claims |
| `JwtTokenProvider` | [AUTH-29] | `generateSuperAdminToken(admin)` | Creates super admin JWT without tenant context |
| `JwtTokenProvider` | [AUTH-29] | `validateToken(token)` | Checks HMAC signature + expiry |
| `JwtTokenProvider` | [AUTH-29] | `getUserIdFromToken(token)` | Extracts user ID from JWT subject |
| `JwtTokenProvider` | [AUTH-29] | `getTenantIdFromToken(token)` | Extracts tenantId claim |
| `JwtTokenProvider` | [AUTH-29] | `getTokenVersionFromToken(token)` | Extracts tokenVersion claim for invalidation |
| `JwtAuthenticationFilter` | [AUTH-30] | `doFilterInternal(request, response, chain)` | JWT extraction, validation, and SecurityContext setup |
| `JwtAuthenticationEntryPoint` | [AUTH-31] | `commence(request, response, exception)` | HTTP 401 handler; returns JSON error response |
| `UserPrincipal` | [AUTH-32] | Constructor | Holds user identity + permissions for Spring Security |
| `AuthService` | [AUTH-13] | `login(request, ipAddress, deviceInfo, response)` | Tenant user login orchestrator (public schema → tenant schema) |
| `AuthService` | [AUTH-13] | `refreshAccessToken(request, httpRequest, response)` | Refresh token flow orchestrator |
| `AuthController` | [AUTH-37] | `login(request, httpRequest, response)` | HTTP endpoint: POST /api/auth/login |
| `AuthController` | [AUTH-37] | `signup(request, response)` | HTTP endpoint: POST /api/auth/signup |
| `AuthController` | [AUTH-37] | `refresh(request, httpRequest, response)` | HTTP endpoint: POST /api/auth/refresh |
| `User` | [AUTH-1] | Field: `tokenVersion` | Invalidation marker; incremented on password reset |
| `TenantContext` | [SHARED-X] | `setTenantId(tenantId)` | ThreadLocal: stores current tenant ID |
| `TenantContext` | [SHARED-X] | `setCurrentSchema(schema)` | ThreadLocal: stores current schema name |

---

## Rules / Constraints

1. **Token Version MUST be cleared in finally block** — If TenantContext is not cleared after login, subsequent requests in the same thread will inherit the tenant context, causing cross-tenant data leakage. Use try-finally in all auth flows.

2. **JWT secret MUST be at least 32 bytes (256 bits) for HS256** — Shorter secrets are cryptographically weak and violate JJWT library requirements. Use `openssl rand -base64 32` to generate.

3. **Refresh token TTL MUST be longer than access token TTL** — If refresh token expires before access token, users cannot refresh. Standard: access = 15 min, refresh = 7 days.

4. **HttpOnly cookies MUST NOT be parsed by JavaScript** — If a cookie lacks HttpOnly flag, malicious JavaScript can steal it via `document.cookie`. Always set HttpOnly=true for auth tokens.

5. **Token version MUST be checked against Redis BEFORE granting access** — Otherwise a revoked token (e.g., password reset) remains valid. Redis key: `{schema}:{tenantId}:{userId}:tokenVersion`.

6. **SUPER_ADMIN tokens MUST NOT contain tenantId claim** — A super admin with tenantId claim could be confused with a tenant user. The `isSuperAdmin: true` flag is the sole discriminator.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| Invalid JWT signature | `JwtException` (JJWT library) | 401 Unauthorized | Filter skips auth, endpoint returns 401 from `@PreAuthorize` or manually |
| Token expired | JWT parsed but `isTokenExpired() == true` | 401 Unauthorized | Client calls POST /api/auth/refresh with refresh token |
| Token version revoked | Redis lookup returns null or mismatch | 401 Unauthorized + `"TOKEN_REVOKED"` | User must log in again |
| Tenant not found (by slug) | `TenantException.notFound()` thrown | 400 Bad Request or 404 | Return error; slug is wrong |
| Tenant suspended | `TenantException.suspended()` thrown | 403 Forbidden | Super admin must reset tenant status; user cannot log in |
| Rate limit exceeded (login) | `LoginRateLimiter.checkBlocked()` throws | 429 Too Many Requests | Wait for IP cooldown (default 15 min) |
| User not found in tenant schema | `UsernameNotFoundException` from TenantAuthService | 401 Unauthorized | Email does not exist in this tenant |
| User password incorrect | `BadCredentialsException` after BCrypt verify | 401 Unauthorized | Retry with correct password |
| User account disabled | `User.status != ACTIVE` check | 403 Forbidden | Super admin must enable account |

---

## Edge Cases

- **Concurrency**: Two simultaneous requests with the same token — both will read the same tokenVersion from Redis concurrently. No race condition because tokenVersion is immutable after claim extraction. The invalidation check is idempotent.

- **Timezone**: JWT expiry is stored as milliseconds since Unix epoch — timezone-independent. If server clock skew occurs (system time changes), tokens may expire early or late. Use NTP to keep clocks synchronized.

- **Tenant Isolation**: If TenantContext is not cleared in the filter's finally block, a second request in the same thread pool worker will inherit the previous tenant's context, causing SQL queries to hit the wrong schema. This is a **critical bug**. All auth flows MUST use try-finally.

- **CSRF**: HttpOnly cookies with `SameSite=Lax` prevent simple CSRF. Endpoints that mutate state (`POST`, `PUT`, `DELETE`) enforce CSRF tokens via `SecurityConfig`. State-less endpoints (`GET`) do not require CSRF tokens but are always safe for reads (no side effects).

- **First-time login**: User entity has `isFirstLogin = true` by default. Frontend can check this flag and redirect to a forced password-change page before granting full app access. Auth module does NOT enforce this — frontend logic.

---

## Known Issues / Limitations

1. **Token issued-at and expiry stored in milliseconds** — Causes minor storage/transmission overhead vs. epoch seconds. No security impact, but wastes ~3 bytes per token.

2. **Refresh token stored as plain text in RefreshToken entity** — In production, this SHOULD be hashed (like JWT subject). Currently allows DB admin to forge refresh tokens. Mitigated by strong database access controls.

3. **No automatic token rotation on every refresh** — Refresh tokens are reused across multiple refresh cycles. One compromised token allows indefinite impersonation if not revoked. A more secure flow would rotate refresh tokens on each use (single-use refresh tokens).

4. **Super admin auth does NOT check if super admin is deleted in DB** — If a PlatformAdmin row is deleted but JWT cache still exists, invalid principals may access endpoints. Frontend/cache expiry mitigates this in practice.

5. **Password reset does NOT log out all active sessions** — After password reset, all tokens remain valid until natural expiry. Token versioning is supposed to revoke them, but the implementation is incomplete in the old codebase. Use token revocation API to force logout.

---

## Future Improvements

1. Use single-use refresh tokens — On each refresh, return a NEW refresh token and expire the old one. Reduces impact of token compromise.

2. Hash refresh token secrets in the RefreshToken entity — Currently stored as plaintext. Hash with bcrypt (like passwords) to prevent database leakage.

3. Implement API key authentication for service-to-service calls — Separate from user JWT; allows microservices to authenticate without user context.

4. Add multi-factor authentication (MFA) layer — After password verification, require TOTP/SMS code before issuing tokens.

5. Log all authentication events (login, refresh, logout, password change) — Audit trail for compliance and incident response.

---

## Related Documents
- [tenant-context-lifecycle.md](../02-multi-tenancy/tenant-context-lifecycle.md) — How TenantContext is managed across request lifecycle
- [authorization-rbac.md](./authorization-rbac.md) — Permission validation using JWT claims + RBAC
- [jwt-token-lifecycle.md](./jwt-token-lifecycle.md) — Detailed token generation, storage, and revocation
- [request-flow.md](../01-architecture/request-flow.md) — End-to-end HTTP request flow through auth filter
- [system-design.md](../01-architecture/system-design.md) — High-level architecture overview
