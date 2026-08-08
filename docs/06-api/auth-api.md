---
version: 1.0
date: 2026-05-14
author: Manoj Pandi
status: Production Ready
tags:
  - api
  - authentication
  - jwt
  - cookies
  - rest
related_documents:
  - ../03-security/authentication.md
  - ../03-security/jwt-token-lifecycle.md (coming)
  - ./error-handling.md
  - ../02-multi-tenancy/multi-tenancy-strategy.md
---

# Authentication API

## Executive Summary

The Authentication API handles tenant and super-admin authentication across signup, login, token refresh, logout, and password reset flows. **All tokens are transmitted via HttpOnly, Secure, SameSite=Lax cookies — never in response bodies**. This document covers request/response formats, error codes, rate limiting, session management, and integration patterns.

---

## Context & Problem

### Authentication Layers

MTBS uses **two-tier authentication**:

| Tier | User | Path | Scope | JWT Claims |
|------|------|------|-------|-----------|
| **Tenant** | `User` in tenant schema | `/api/auth/{login,refresh,logout,signup,me}` | Single tenant | `userId`, `tenantId`, `roleId`, `tokenVersion` |
| **Platform** | `PlatformAdmin` in public schema | `/api/admin/login` | All tenants | `adminId`, `tokenVersion` |

Tenant logins include **rate limiting** (5 failures per IP → 15-minute lockout) and **two-step tenant resolution** (resolve slug from email first).

### Cookie Security Model

```
Access token:  HttpOnly, Secure, SameSite=Lax, path=/api
Refresh token: HttpOnly, Secure, SameSite=Lax, path=/api/auth/refresh (scoped)
```

- **HttpOnly** — JS cannot access (XSS-proof)
- **Secure** — HTTPS only (man-in-the-middle proof)
- **SameSite=Lax** — Sent with cross-site requests for navigation (CSRF-proof, except form POST)
- **Scoped refresh path** — Refresh token only sent to `/api/auth/refresh` endpoint (defense-in-depth)

---

## Dependencies

### Inbound (Who calls auth endpoints)

- **Frontend (React)** — Browser automatically sends cookies; no manual header injection needed
- **Mobile clients** — Must configure HTTP client to accept/send cookies (e.g., `withCredentials: true` in axios)
- **API clients** — Must include `Cookie` header in requests

### Outbound (What auth depends on)

- `AuthService` — Orchestrates login, token refresh, logout (public schema routing)
- `TenantAuthService` — Tenant-schema-scoped auth operations
- `JwtTokenProvider` — Generates/validates access + refresh tokens
- `PasswordResetService` — Password reset flow
- `SignupService` — Tenant account creation + schema provisioning
- `LoginRateLimiter` — IP-based rate limiting (5 failures → 15-min lockout)
- `SlugCacheService` / `SchemaCacheService` — Redis caching for tenant resolution
- `TenantContext` — Sets `tenantId` + `schemaName` for tenant-scoped queries

### Configuration

```yaml
spring:
  security:
    jwt:
      secret: ${JWT_SECRET}                  # Min 32 bytes for HS256
      access-token-expiry: 900               # 15 minutes
      refresh-token-expiry: 2592000          # 30 days
    http:
      only: true                             # HttpOnly cookie flag
      secure: true                           # Secure flag (HTTPS)
  mail:
    host: smtp.gmail.com
    port: 587
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

---

## Endpoints

### POST /api/v1/auth/signup

**Purpose:** Create tenant account and provision schema

**Request:**
```json
{
  "name": "Acme Inc",
  "email": "admin@acme.com",
  "password": "SecurePass123!"
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "user": {
      "userId": 1,
      "email": "admin@acme.com",
      "role": "OWNER",
      "permissions": ["TENANT_VIEW", "TENANT_MANAGE", ...]
    },
    "tenant": {
      "tenantId": 456,
      "tenantName": "Acme Inc"
    },
    "session": {
      "issuedAt": "2026-05-14T10:30:00Z",
      "expiresAt": "2026-05-14T10:45:00Z",
      "isFirstLogin": true
    },
    "flags": {
      "isTrial": true,
      "requiresOnboarding": true
    }
  },
  "message": "Account created. Please complete onboarding."
}
```

**Side effects:**
- Creates `Tenant` in public schema (status: `PENDING_ONBOARDING`)
- Provisions new PostgreSQL schema `s_456`
- Runs Flyway migrations (V1-V20) in tenant schema
- Creates system roles (OWNER, ADMIN, EMPLOYEE)
- Creates `User` (name, email, password) with role=OWNER
- Sets HttpOnly cookies: `accessToken`, `refreshToken`
- Publishes `TenantCreatedEvent` for audit/notifications

**Error codes:**
- `400` — Invalid email format, password too short, missing fields
- `409` — Email already registered across any tenant
- `500` — Schema provisioning failed (admin intervention required)

**Validation:**
```java
// SignupRequest
@NotBlank name                           // Required
@Email email                             // Valid email format
@Size(min=8) password                   // Min 8 chars
```

---

### POST /api/v1/auth/tenants

**Purpose:** Resolve tenant slugs for two-step login UX

**Request:**
```json
{
  "email": "user@acme.com"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "tenants": [
      {
        "slug": "acme-inc",
        "tenantName": "Acme Inc",
        "isTrial": false
      },
      {
        "slug": "personal-project",
        "tenantName": "My Personal Project",
        "isTrial": true
      }
    ]
  },
  "message": "Tenants resolved"
}
```

**Security:**
- **Always returns 200** — even if email not found (prevents email enumeration)
- Result cached in Redis (key: `perms:{email}`, TTL: 1 hour)

**Use case — two-step login flow:**
1. Frontend asks user for email → calls `POST /api/auth/tenants`
2. Frontend shows list of tenant options
3. User picks tenant → frontend calls `POST /api/auth/login` with email + slug

---

### POST /api/v1/auth/login

**Purpose:** Authenticate tenant user and issue tokens

**Request:**
```json
{
  "email": "user@acme.com",
  "password": "SecurePass123!",
  "tenantSlug": "acme-inc"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "user": {
      "userId": 42,
      "email": "user@acme.com",
      "role": "ADMIN",
      "permissions": ["USER_VIEW", "USER_MANAGE", "BILLING_MANAGE"]
    },
    "tenant": {
      "tenantId": 456,
      "tenantName": "Acme Inc"
    },
    "session": {
      "issuedAt": "2026-05-14T10:35:00Z",
      "expiresAt": "2026-05-14T10:50:00Z",
      "isFirstLogin": false
    },
    "flags": {
      "isTrial": false,
      "requiresOnboarding": false
    }
  },
  "message": "Login successful"
}
```

**Side effects:**
- Sets HttpOnly cookies: `accessToken` (15 min), `refreshToken` (30 days)
- Creates/updates `RefreshToken` row in tenant schema (token rotation)
- Records login in `AuditLog` (success)
- Clears failed login counter for this IP

**Error codes:**
- `400` — Missing email, password, or tenantSlug
- `401` — Invalid credentials (email not found OR password mismatch)
- `401` — User status=INACTIVE/LOCKED
- `403` — Tenant is SUSPENDED or INACTIVE
- `429` — Too many failed login attempts from this IP (5+ failures → 15-min lockout)
- See `AuthException` error codes in [error-handling.md](./error-handling.md#auth-error-codes)

**Validation:**
```java
// LoginRequest
@Email email
@NotBlank password
@Size(min=2, max=50) @Pattern("^[a-z0-9-]+$") tenantSlug
```

**Rate limiting:**
```
Per IP:
  Max 5 failed attempts per 15 minutes
  6th failure → HTTP 429 + "Too many requests. Try again in Xs."
  Lockout persists across all tenants for that IP
  Success → counter reset to 0
```

**Audit trail:**
```sql
-- AuditLog entry
INSERT INTO public.audit_logs
  (tenant_id, user_id, action, entity_type, entity_id, ip_address, description)
VALUES (456, 42, LOGIN, USER, '42', '203.0.113.45', 'Login from Chrome on macOS')
```

---

### POST /api/v1/auth/refresh

**Purpose:** Issue new access token using refresh token

**Request:**
```json
{
  "refreshToken": "(optional — default from cookie)",
  "tenantSlug": "acme-inc"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "user": {
      "userId": 42,
      "email": "user@acme.com",
      "role": "ADMIN",
      "permissions": [...]
    },
    "tenant": {
      "tenantId": 456,
      "tenantName": "Acme Inc"
    },
    "session": {
      "issuedAt": "2026-05-14T10:45:00Z",
      "expiresAt": "2026-05-14T11:00:00Z",
      "isFirstLogin": false
    },
    "flags": {...}
  },
  "message": "Token refreshed successfully"
}
```

**Side effects:**
- Validates refresh token (not expired, not revoked, correct version)
- Generates new access token (15 min expiry)
- **Rotates refresh token** — old token marked as `replacedBy`, new token issued
- Sets new HttpOnly cookies
- Updates `RefreshToken.replacedBy` chain for audit

**Error codes:**
- `400` — Missing tenantSlug
- `401` — Refresh token missing/malformed
- `401` — Refresh token expired (>30 days old)
- `401` — Refresh token revoked (password changed, or manually invalidated)
- `401` — Token version mismatch (user changed password, invalidating all tokens)
- `403` — Tenant is SUSPENDED

**Token rotation mechanism (for security):**

```
Scenario: User changes password
1. PasswordResetService.resetPassword() increments User.tokenVersion (1 → 2)
2. All existing refresh tokens become invalid
3. Next API call with old access token → JwtTokenProvider checks tokenVersion → FAIL
4. Frontend calls /refresh with old refresh token → version check → FAIL
5. User forced to re-login
```

**Refresh token chain tracking:**

```java
// RefreshToken entity
private String token;           // Current token
private String replacedBy;      // Token that replaced this one (for audit)
private Instant expiryDate;     // Expiry for this token + all descendants

// Token rotation flow
OLD token (v1) → marked replacedBy=NEW token (v2)
NEW token (v2) → marked replacedBy=NEWER token (v3)
...and so on for audit trail
```

---

### POST /api/v1/auth/logout

**Purpose:** Revoke refresh token and clear cookies

**Request:**
```json
{
  "refreshToken": "(optional — default from cookie)"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": null,
  "message": "Logged out successfully"
}
```

**Side effects:**
- Marks `RefreshToken` as revoked
- Clears HttpOnly cookies (`accessToken`, `refreshToken`)
- Records logout in `AuditLog`
- Access token still valid until expiry (15 min) — frontend should not use it after logout
- Refresh token immediately invalid

**Error codes:**
- `200` — Even if refresh token missing/invalid (idempotent operation)

**Important — Access token is NOT revoked:**

```
Timeline:
00:00 - User logs out → refresh token revoked, cookies cleared
00:01 - User makes API call with old access token (still in cookies or memory)
00:01 - Access token still valid (hasn't expired yet)
00:01 - API returns 401 (or user is suddenly "logged back in")
```

**Mitigation:** Frontend must clear its auth state immediately on logout (don't rely on token expiry).

---

### GET /api/v1/auth/me

**Purpose:** Get current authenticated user's profile

**Request:**
```
GET /api/v1/auth/me
Cookie: accessToken=...
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "userId": 42,
    "name": "John Doe",
    "email": "john@acme.com",
    "role": "ADMIN",
    "status": "ACTIVE",
    "tenantId": 456,
    "tenantName": "Acme Inc",
    "schemaName": "s_456",
    "permissions": ["USER_VIEW", "USER_MANAGE", "BILLING_MANAGE"],
    "createdAt": "2026-04-01T08:00:00Z"
  },
  "message": "Profile fetched successfully"
}
```

**Security:**
- **Requires valid JWT** — 401 if missing/invalid/expired
- Returns only current user's data (cannot query other users)

---

### POST /api/v1/auth/forgot-password

**Purpose:** Request password reset email

**Request:**
```json
{
  "tenantSlug": "acme-inc",
  "email": "user@acme.com"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": null,
  "message": "If that email is registered, a reset link has been sent."
}
```

**Security — Email enumeration prevention:**
- **Always returns 200** — even if email not found
- No response body indicates whether email exists
- This prevents attackers from discovering valid emails

**Side effects:**
- If email found: generates single-use reset token, stores in `PasswordResetToken` table with 15-min expiry
- Sends email with reset link: `https://app.example.com/reset?token={token}`
- Records attempt in `AuditLog` (without revealing success/failure)

**Error codes:**
- `400` — Invalid tenantSlug or email format
- `200` — Always (even if email not found)

**Reset token:**
```sql
-- PasswordResetToken table (in tenant schema)
CREATE TABLE password_reset_tokens (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  token VARCHAR(256) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,        -- NOW() + 15 minutes
  used_at TIMESTAMP,                    -- Single-use: NULL until used
  created_at TIMESTAMP NOT NULL
);
```

---

### POST /api/v1/auth/reset-password

**Purpose:** Reset password using reset token

**Request:**
```json
{
  "tenantSlug": "acme-inc",
  "token": "eyJ0eXAi...(reset token from email)",
  "newPassword": "NewSecurePass456!"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": null,
  "message": "Password reset successfully. Please log in."
}
```

**Side effects:**
- Validates reset token (exists, not expired, not already used)
- Hashes new password (BCrypt)
- Updates `User.password` in tenant schema
- **Increments `User.tokenVersion`** — all existing refresh tokens become invalid
- Marks reset token as used (`used_at` = NOW())
- Records in `AuditLog` (password changed)
- User forced to re-login

**Error codes:**
- `400` — Invalid tenantSlug, token missing
- `400` — New password too weak (<8 chars)
- `400` — Reset token invalid (not found)
- `400` — Reset token expired (>15 mins old)
- `400` — Reset token already used (single-use)

**Validation:**
```java
// ResetPasswordRequest
@NotBlank tenantSlug
@NotBlank token
@Size(min=8) newPassword
```

**Token invalidation cascade:**

```
Password reset → User.tokenVersion incremented (1 → 2)
  ↓
All refresh tokens become invalid (version mismatch)
  ↓
User attempts refresh → version check fails → 401
  ↓
User forced to login again with new password
```

---

## Authentication Flow Diagrams

### Signup → Onboarding Flow

```
┌─ Frontend ─────────┐
│ POST /signup       │
│ (name, email, pwd) │
└────────┬───────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│ SignupService.signup()                                  │
│ ✓ Create Tenant in public schema (PENDING_ONBOARDING) │
│ ✓ Provision schema: CREATE SCHEMA s_456                │
│ ✓ Run Flyway migrations (V1-V20)                       │
│ ✓ Create system roles (OWNER, ADMIN, EMPLOYEE)        │
│ ✓ Create User (OWNER role)                             │
│ ✓ Set HttpOnly cookies (accessToken, refreshToken)    │
│ ✓ Publish TenantCreatedEvent                           │
└────────┬───────────────────────────────────────────────┘
         │
         ▼ AuthResponse (201 Created)
┌─ Frontend ─────────────────────────────────────────────┐
│ Set isFirstLogin flag                                  │
│ Set requiresOnboarding=true flag                       │
│ Redirect: /onboarding/company (Step 1 of wizard)      │
└─────────────────────────────────────────────────────────┘
```

### Login Flow

```
┌─ Frontend ─────────┐
│ GET /auth/tenants  │
│ (email)            │
└────────┬───────────┘
         │
         ▼
┌────────────────────────────────────────┐
│ AuthService.resolveTenantsForEmail()   │
│ ✓ Cache lookup: perms:{email}          │
│ ✓ Return tenant slugs (or empty list) │
└────────┬───────────────────────────────┘
         │
         ▼ TenantResolutionResponse
┌─ Frontend ─────────────────────┐
│ Show dropdown: [Acme Inc, ...] │
│ User picks: "Acme Inc"         │
└─────────────┬──────────────────┘
              │
              ▼ POST /login
       ┌──────────────────┐
       │ email            │
       │ password         │
       │ tenantSlug       │
       └────────┬─────────┘
                │
                ▼
    ┌───────────────────────────────────────────────────────┐
    │ AuthService.login()                                   │
    │ ✓ Resolve tenantId from slug (slug cache → DB)       │
    │ ✓ Load Tenant (check SUSPENDED/INACTIVE)             │
    │ ✓ Resolve schemaName (schema cache → DB)             │
    │ ✓ Set TenantContext                                  │
    │                                                       │
    │ TenantAuthService.loginInTenantSchema()              │
    │ ✓ Find User by email in tenant schema                │
    │ ✓ Verify password (BCrypt)                           │
    │ ✓ Check User status (not LOCKED/INACTIVE)            │
    │ ✓ Generate access + refresh tokens                   │
    │ ✓ Create RefreshToken row (token rotation)           │
    │ ✓ Set HttpOnly cookies                               │
    │ ✓ Record login in AuditLog                           │
    │ ✓ Clear failed login counter                         │
    └────────┬──────────────────────────────────────────────┘
             │
             ▼ AuthResponse (200 OK)
    ┌─ Frontend ─────────────────────────────────┐
    │ Store permissions in memory (not localStorage!) │
    │ Redirect to dashboard                    │
    │ All subsequent requests include cookies │
    └────────────────────────────────────────────┘
```

### Token Refresh Flow

```
┌─ Frontend ──────────────┐
│ Access token expired    │
│ (15 minutes)            │
│ Request to /api/...     │
└────────┬────────────────┘
         │ 401 Unauthorized
         ▼
┌─ Frontend ──────────────┐
│ POST /auth/refresh      │
│ (cookie: refreshToken)  │
└────────┬────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────┐
│ AuthService.refreshAccessToken()                   │
│ ✓ Extract refresh token from cookie                │
│ ✓ Resolve tenantId from slug                       │
│ ✓ Load Tenant (check status)                       │
│ ✓ Set TenantContext                                │
│                                                    │
│ TenantAuthService.refreshInTenantSchema()         │
│ ✓ Validate refresh token:                          │
│   - Token exists in DB                             │
│   - Not revoked (revoked=false)                     │
│   - Not expired (expiryDate > NOW)                 │
│   - Token version matches User.tokenVersion       │
│ ✓ Generate new access token (15 min)              │
│ ✓ Rotate refresh token:                            │
│   - Mark old token replacedBy=new token            │
│   - Create new token row                           │
│ ✓ Set new HttpOnly cookies                         │
└────────┬─────────────────────────────────────────┘
         │
         ▼ AuthResponse (200 OK)
┌─ Frontend ──────────────────────────────────┐
│ New access token in cookie                  │
│ Retry original failed request (automatic)   │
│ Request succeeds (200)                      │
└─────────────────────────────────────────────┘
```

### Password Reset Flow

```
┌─ User ──────────────┐
│ Forgot password      │
│ Click "Reset"        │
└────────┬─────────────┘
         │
         ▼ POST /forgot-password
┌─────────────────────────────────┐
│ tenantSlug                       │
│ email                            │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│ PasswordResetService.requestPasswordReset()         │
│ ✓ Find User by email (no error if not found)       │
│ ✓ Generate single-use reset token (256-char)       │
│ ✓ Store in PasswordResetToken table (15 min TTL)  │
│ ✓ Send email with reset link                       │
│ ✓ Record in AuditLog                               │
│ ✓ Return 200 OK (regardless of result)            │
└────────┬────────────────────────────────────────────┘
         │
         ▼ (200 OK, no indication of success)
┌─ User ──────────────────────────────────┐
│ Receives email (if registered)           │
│ Clicks reset link in email               │
│ https://app.example.com/reset?token=...  │
└────────┬───────────────────────────────┘
         │
         ▼ POST /reset-password
┌──────────────────────────────────┐
│ tenantSlug                        │
│ token (from URL param)            │
│ newPassword                       │
└────────┬─────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────┐
│ PasswordResetService.resetPassword()                 │
│ ✓ Find PasswordResetToken by token                   │
│ ✓ Check not expired (<15 mins)                       │
│ ✓ Check not already used                            │
│ ✓ Hash new password                                 │
│ ✓ Update User.password                              │
│ ✓ Increment User.tokenVersion (all tokens invalid)  │
│ ✓ Mark reset token as used                          │
│ ✓ Record in AuditLog                                │
└────────┬─────────────────────────────────────────────┘
         │
         ▼ 200 OK
┌─ User ──────────────────────────────────┐
│ "Password reset successfully"            │
│ Redirect to login page                   │
│ Login with new password                  │
└──────────────────────────────────────────┘
```

---

## Error Handling

### Auth-Specific Error Codes

See [error-handling.md](./error-handling.md#auth-error-codes) for complete list.

**Common errors:**

| Error Code | HTTP | Message | Cause |
|------------|------|---------|-------|
| `AUTH_001` | 401 | Invalid credentials | Email not found OR password mismatch |
| `AUTH_002` | 401 | Token expired | Access token >15 mins old OR refresh token >30 days old |
| `AUTH_003` | 403 | Access denied | User lacks required permission |
| `AUTH_004` | 423 | Account locked | User status=LOCKED (too many failed password attempts) |
| `AUTH_005` | 423 | Account disabled | User status=INACTIVE |
| `AUTH_006` | 401 | Token invalid | Malformed JWT OR signature verification failed |
| `AUTH_007` | 401 | Reset token invalid | Token not found OR already used |
| `AUTH_008` | 401 | Reset token expired | Token >15 mins old |
| `AUTH_009` | 429 | Too many requests | IP exceeded max failed login attempts |

**Example error response:**

```json
{
  "success": false,
  "error": {
    "code": "AUTH_001",
    "message": "Invalid credentials",
    "detail": "Email or password is incorrect"
  },
  "timestamp": "2026-05-14T10:35:00Z"
}
```

---

## Security Considerations

### 1. CSRF Protection

**Pattern:** SameSite=Lax cookies + token validation

```
Frontend makes POST /api/auth/login from same domain
  ↓
Browser includes cookies (SameSite=Lax allows same-site POST)
  ↓
Server validates token in JWT
  ↓
Form POST from 3rd-party domain: cookies NOT sent
  ↓
Request fails (no cookies = no valid token)
```

### 2. XSS Protection

**Pattern:** HttpOnly cookies (JS cannot access)

```javascript
// Attacker injects JS into app
<script>
  console.log(document.cookie);  // ✗ HttpOnly cookies are HIDDEN
  // Returns: empty (or only non-HttpOnly cookies)
</script>

// Later, browser sends request to /api
fetch('/api/me', { credentials: 'include' })
  // ✓ Browser AUTOMATICALLY adds HttpOnly cookies
  // Attacker cannot intercept them
```

### 3. Token Version Invalidation

**Pattern:** Version field in JWT + increment on password change

```
User changes password:
  1. User.tokenVersion increments (1 → 2)
  2. All existing JWTs contain version=1 (now stale)

Next request:
  3. JwtTokenProvider extracts version=1 from JWT
  4. Compares to User.tokenVersion=2 in Redis
  5. Mismatch → throw TokenException
  6. User forced to re-login
```

### 4. Refresh Token Rotation

**Pattern:** Single-use tokens with replacement chain

```
Scenario: Attacker steals old refresh token

Step 1: Attacker uses old token → /refresh
  - Old token marks replacedBy=NEW token (v2)
  - Attacker gets new access + refresh (v2)

Step 2: Legitimate user refreshes
  - User's old token also v1 (same as attacker's)
  - Both attempt refresh
  - Second user gets v3 (newer)
  - System detects token reuse: old v1 marked replacedBy=v2 AND v3
  - ALERT: Token compromise detected
```

### 5. Rate Limiting

**Pattern:** IP-based tracking with exponential backoff

```
IP 203.0.113.45:
  Attempt 1 (12:00): FAIL
  Attempt 2 (12:01): FAIL
  Attempt 3 (12:02): FAIL
  Attempt 4 (12:03): FAIL
  Attempt 5 (12:04): FAIL ← 5 attempts
  Attempt 6 (12:05): 429 Too Many Requests ← lockout triggered
  
  Lockout window: 12:05 - 12:20 (15 minutes)
  
  Request at 12:19: still locked (19 mins)
  Request at 12:21: allowed (lockout expired)
```

**Key:** Lockout is per IP (not per email), prevents brute force across multiple user accounts.

### 6. Email Enumeration Prevention

**Pattern:** Always return 200 for forgot-password/reset

```
Request: POST /forgot-password?email=user@acme.com

Scenario A: Email exists
  Response: 200 OK "If email registered, reset link sent"
  Action: Generate token, send email

Scenario B: Email NOT exists
  Response: 200 OK "If email registered, reset link sent"
  Action: No email sent (attacker cannot tell difference)
```

---

## Integration Patterns

### Frontend: React + Axios

```typescript
// 1. Configure axios to include cookies
const axiosClient = axios.create({
  baseURL: 'https://api.example.com',
  withCredentials: true  // ✓ Include cookies in requests
});

// 2. Login
const handleLogin = async (email: string, password: string, tenantSlug: string) => {
  try {
    const response = await axiosClient.post('/api/v1/auth/login', {
      email,
      password,
      tenantSlug
    });
    // Cookies set automatically by server
    // NO need to localStorage.setItem('token', ...) 
    
    // Store user data in memory (useContext or Redux)
    setAuthUser(response.data.data.user);
  } catch (error) {
    if (error.response?.status === 429) {
      showError('Too many attempts. Try again in 15 minutes.');
    } else if (error.response?.status === 401) {
      showError('Invalid email or password');
    }
  }
};

// 3. Automatic refresh on 401
axiosClient.interceptors.response.use(
  response => response,
  async error => {
    if (error.response?.status === 401) {
      try {
        // Automatically refresh token
        await axiosClient.post('/api/v1/auth/refresh', {
          // Don't send refreshToken in body — it's in the cookie
        });
        // Retry original request (cookies now updated)
        return axiosClient(error.config);
      } catch {
        // Refresh failed → force re-login
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

// 4. Logout
const handleLogout = async () => {
  await axiosClient.post('/api/v1/auth/logout');
  // Cookies cleared by server
  setAuthUser(null);
  window.location.href = '/login';
};
```

### Frontend: React + TanStack Query

```typescript
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        // Retry on 401 once (for token refresh)
        return error.response?.status === 401 && failureCount < 1;
      }
    }
  }
});

// Auto-refresh on 401
const apiClient = axios.create({ withCredentials: true });
apiClient.interceptors.response.use(null, async error => {
  if (error.response?.status === 401) {
    try {
      await apiClient.post('/api/auth/refresh');
      queryClient.invalidateQueries(); // Retry all queries
      return apiClient(error.config);
    } catch {
      queryClient.clear();
      window.location.href = '/login';
    }
  }
  return Promise.reject(error);
});
```

### Backend: Testing Auth

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuthControllerTest {
    
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    
    @Test
    void testLoginSuccess() throws Exception {
        // Setup: create user
        User user = User.builder()
            .email("user@acme.com")
            .password(passwordEncoder.encode("SecurePass123!"))
            .role(roleRepository.findByName("ADMIN"))
            .status(Status.ACTIVE)
            .tenantId(456L)
            .build();
        userRepository.save(user);
        
        // Execute
        mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "user@acme.com",
                  "password": "SecurePass123!",
                  "tenantSlug": "acme-inc"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.email").value("user@acme.com"))
            .andExpect(cookie().exists("accessToken"))
            .andExpect(cookie().httpOnly("accessToken", true));
    }
    
    @Test
    void testLoginRateLimited() throws Exception {
        // Attempt 6 logins (5 allowed)
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                .remoteAddress("203.0.113.45")  // Same IP
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"x","password":"x","tenantSlug":"x"}
                    """));
        }
        
        // 6th attempt should be blocked
        mockMvc.perform(post("/api/v1/auth/login")
            .remoteAddress("203.0.113.45")
            .contentType(MediaType.APPLICATION_JSON)
            .content("..."))
            .andExpect(status().isTooManyRequests());  // 429
    }
}
```

---

## Troubleshooting

### Issue: Cookies not set after login

**Cause:** Frontend not configured for credentials

**Fix:**
```typescript
// ✗ WRONG
fetch('/api/auth/login', { method: 'POST', body: JSON.stringify(...) })

// ✓ CORRECT
fetch('/api/auth/login', {
  method: 'POST',
  credentials: 'include',  // Enable cookies
  body: JSON.stringify(...)
})
```

### Issue: 401 after refresh, then login works

**Cause:** Token version incremented (password changed) but old token still used

**Fix:** Refresh token before calling any protected endpoint after password reset
```typescript
// After password reset
await axiosClient.post('/api/auth/refresh');  // Get new tokens
```

### Issue: "Too many requests" immediately on first login

**Cause:** Brute-force lockout from another service/IP

**Fix:** Wait 15 minutes or contact admin to reset rate limiter

---

## Summary

| Feature | Implementation | Security |
|---------|-----------------|----------|
| Tokens | JWT (HS256) in HttpOnly cookies | XSS-proof, CSRF-proof |
| Expiry | Access 15 min, Refresh 30 days | Time-limited exposure |
| Rate limiting | 5 failures/IP → 15 min lockout | Brute-force resistant |
| Password reset | Single-use token, 15 min expiry | Prevents replay attacks |
| Token rotation | Old → New chain on each refresh | Detects token compromise |
| Version invalidation | Increment on password change | Immediate token revocation |
| Email enumeration | Always 200 on forgot-password | Prevents user enumeration |

