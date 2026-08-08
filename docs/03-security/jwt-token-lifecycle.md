---
version: 1.0
date: 2026-05-14
author: Manoj Pandi
status: Production Ready
tags:
  - jwt
  - security
  - authentication
  - tokens
  - session-management
related_documents:
  - ./auth-api.md
  - ../09-security/authentication.md
  - ../09-security/authorization.md
  - ./error-handling.md
---

# JWT Token Lifecycle

## Executive Summary

MTBS uses JWT (JSON Web Tokens) with HS256 (HMAC-SHA256) signing for stateless authentication. Tokens include claims for user identity, tenant isolation, role-based permissions, and a version field for immediate revocation. This document explains token generation, validation, versioning, rotation, and security considerations.

---

## Context & Problem

### Token Types

MTBS uses two token types:

| Token | Duration | Storage | Refresh? |
|-------|----------|---------|----------|
| **Access Token** | 15 minutes | HttpOnly cookie | Yes, via refresh token |
| **Refresh Token** | 7 days | Database | Rotated on each use |

**Why two tokens?**
- Short-lived access tokens minimize compromise window
- Refresh tokens enable continuous sessions without re-login
- Database refresh tokens enable server-side revocation

---

## Token Structure

### Access Token (JWT)

```
Header:
  {
    "alg": "HS256",
    "typ": "JWT"
  }

Payload:
  {
    "sub": "123",                    // User ID
    "tenantId": 456,                 // Tenant ID (for data isolation)
    "roleId": 2,                     // Role ID (for RBAC)
    "tokenVersion": 5,               // Incremented on password change
    "typ": "ACCESS",                 // Token type
    "iss": "mtbs",                   // Issuer
    "aud": ["mtbs-users"],          // Audience
    "iat": 1715666400,              // Issued at
    "exp": 1715667300               // Expires at (15 min = 900 sec)
  }

Signature:
  HMACSHA256(
    base64UrlEncode(header) + "." +
    base64UrlEncode(payload),
    secret_key
  )
```

### Refresh Token (Not JWT)

```
{
  "token": "550e8400-e29b-41d4-a716-446655440000",  // UUID
  "user_id": 123,
  "revoked": false,
  "expiry_date": "2026-05-21T14:30:00Z",            // 7 days
  "created_at": "2026-05-14T14:30:00Z"
}
```

---

## Token Generation Flow

### Scenario 1: First Login

```
User Input:
  email: "user@acme.com"
  password: "secret123"

1. AuthService.login():
   ├─ Fetch User by email
   ├─ Validate password (BCrypt)
   ├─ Fetch User.tokenVersion (default 0)
   ├─ Fetch Role and Permissions
   │
   ├─ JwtTokenProvider.generateToken(userId, tenantId, roleId, tokenVersion):
   │   └─ Create JWT with 15-min expiry
   │
   ├─ RefreshTokenService.createRefreshToken(user):
   │   ├─ Revoke all existing refresh tokens (single active session)
   │   ├─ Generate new UUID
   │   └─ Set 7-day expiry
   │
   └─ Response:
       └─ Set HttpOnly cookies:
           ├─ accessToken (15 min)
           ├─ refreshToken (7 days, path-scoped)
```

### Scenario 2: Token Refresh

```
Client Request:
  POST /api/auth/refresh
  Cookie: refreshToken=550e8400-e29b-41d4-a716-446655440000

1. AuthService.refresh():
   ├─ Extract refreshToken from cookie
   ├─ RefreshTokenService.validateRefreshToken(token):
   │   ├─ Find token in DB
   │   ├─ Check if revoked
   │   ├─ Check if expired
   │   └─ Throw if invalid
   │
   ├─ Fetch User and Role
   ├─ Fetch current User.tokenVersion
   │
   ├─ JwtTokenProvider.generateToken():
   │   └─ Generate new access token with current tokenVersion
   │
   ├─ RefreshTokenService.rotateRefreshToken():
   │   ├─ Mark old token as revoked
   │   ├─ Generate new token with 7-day expiry
   │   └─ Store in DB
   │
   └─ Response:
       └─ Set new HttpOnly cookies
```

---

## Token Validation Flow

### Every Request

```
HTTP Request received:
  GET /api/v1/subscriptions/current
  Cookie: accessToken=eyJhbGc...

1. JwtAuthenticationFilter.doFilterInternal():
   │
   ├─ Extract JWT from:
   │   ├─ HttpOnly cookie (preferred)
   │   └─ Authorization header (fallback)
   │
   ├─ JwtTokenProvider.validateToken(jwt):
   │   ├─ Parse JWT signature
   │   ├─ Verify HMAC-SHA256 signature
   │   ├─ Check expiration (iat + 15min > now)
   │   └─ Return true/false
   │
   ├─ IF invalid OR expired:
   │   └─ Filter chain continues (Unauthenticated)
   │
   ├─ IF valid:
   │   ├─ Extract claims:
   │   │   ├─ userId = "123"
   │   │   ├─ tenantId = "456"
   │   │   ├─ roleId = "2"
   │   │   └─ tokenVersion = "5"
   │   │
   │   ├─ TenantContext.set(tenantId, schemaName):
   │   │   └─ Thread-local storage for multitenancy
   │   │
   │   ├─ CRITICAL: Validate token version:
   │   │   ├─ Fetch User.tokenVersion from Redis cache
   │   │   ├─ Compare jwt.tokenVersion == cached.tokenVersion
   │   │   ├─ IF mismatch:
   │   │   │   ├─ Log "Rejected revoked token"
   │   │   │   └─ Return 401 UNAUTHORIZED
   │   │   └─ IF match: Continue
   │   │
   │   ├─ Load permissions from cache:
   │   │   └─ Cache key: "perms:{schemaName}:{userId}"
   │   │
   │   ├─ Create UserPrincipal with permissions
   │   └─ Set in SecurityContext
   │
   └─ Proceed to controller
```

**Critical validation:** Token version checking enables **immediate revocation** without blacklisting.

---

## Token Version & Immediate Revocation

### Problem

Classic JWT blacklisting:
```
User changes password at 14:30:00
  ├─ Attacker still has old token (expires at 14:45:00)
  ├─ Blacklist token in cache
  └─ 15 minutes of vulnerability window
```

### Solution: Token Versioning

```
User table:
  {
    "id": 123,
    "email": "user@acme.com",
    "password_hash": "$2a$10$...",
    "token_version": 5,           ← CRITICAL
    "updated_at": "2026-05-14T14:30:00Z"
  }

Old JWT (compromised):
  {
    "sub": "123",
    "tokenVersion": 4             ← Version doesn't match
  }

New JWT (after password change):
  {
    "sub": "123",
    "tokenVersion": 5             ← Matches current
  }
```

### Workflow: Password Change

```
User changes password:
  PATCH /api/auth/profile/password

1. AuthService.changePassword():
   │
   ├─ Validate old password
   ├─ Hash new password
   │
   ├─ Update User:
   │   ├─ Set password = hash(newPassword)
   │   ├─ Increment tokenVersion++ (was 4, now 5)
   │   └─ userRepository.save(user)
   │
   ├─ Clear token version cache:
   │   └─ Redis DELETE "token_version:456:123"
   │
   ├─ All existing tokens INVALID immediately:
   │   ├─ Old token.tokenVersion = 4
   │   ├─ New User.tokenVersion = 5
   │   ├─ Filter rejects: 4 != 5
   │   └─ Return 401
   │
   └─ User must login again
       └─ New token generated with tokenVersion = 5
```

**No blacklist needed** — token version mismatch invalidates all old tokens instantly.

---

## Refresh Token Rotation

### Design

```
Goal: Detect token compromise via rotation chain

Compromise Scenario:
  Attacker steals user's refreshToken
    │
    ├─ User continues using app
    │   ├─ Next refresh: generates new token & cookie
    │   ├─ Old token marked revoked in DB
    │   └─ User's session continues (new token works)
    │
    └─ Attacker tries to use stolen token
        ├─ Check DB: revoked = true
        ├─ Token rejected: TOKEN_REVOKED
        └─ Alert: Possible compromise detected
```

### Rotation Mechanics

```
Time: 14:00 - User logs in
  RefreshToken 1 (UUID-111):
    token: "UUID-111"
    revoked: false
    user_id: 123

Time: 14:15 - POST /refresh
  1. Validate RefreshToken 1 (valid, not revoked)
  2. Mark RefreshToken 1: revoked = true
  3. Create RefreshToken 2 (UUID-222):
     token: "UUID-222"
     revoked: false
     user_id: 123
  4. Return new cookie with UUID-222

Time: 14:30 - Attacker uses stolen UUID-111
  1. Lookup RefreshToken 1
  2. Check: revoked = true
  3. Reject: TOKEN_REVOKED (401)
  4. Log alert: Possible compromise
```

### Implementation

```java
// Validate refresh token (AuthService.refresh)
@Transactional
public AuthResponse refresh(String refreshTokenValue) {
  RefreshToken rt = refreshTokenService.validateRefreshToken(refreshTokenValue);
  
  User user = rt.getUser();
  Tenant tenant = user.getTenant();
  Role role = user.getRole();
  
  // Generate new access token
  String newAccessToken = jwtTokenProvider.generateToken(
    user.getId(), tenant.getId(), role.getId(), user.getTokenVersion());
  
  // Rotate refresh token
  RefreshToken newRefreshToken = refreshTokenService.rotateRefreshToken(user);
  
  return AuthResponse.builder()
    .accessToken(newAccessToken)
    .refreshToken(newRefreshToken.getToken())
    .expiresIn(jwtTokenProvider.getJwtExpiration())
    .build();
}

// Rotate refresh token
@Transactional
public RefreshToken rotateRefreshToken(User user) {
  // Revoke all old tokens for this user
  revokeAllUserTokens(user);
  
  // Create new token
  return createRefreshToken(user);
}
```

---

## Token Claims & Security

### Access Token Claims

```json
{
  "sub": "123",              // CRITICAL: User ID for data loading
  "tenantId": 456,           // CRITICAL: Tenant isolation
  "roleId": 2,               // CRITICAL: Permission loading
  "tokenVersion": 5,         // CRITICAL: Immediate revocation
  "typ": "ACCESS",           // Token type identifier
  "iss": "mtbs",             // Issuer (for verification)
  "aud": ["mtbs-users"],     // Audience (for verification)
  "iat": 1715666400,         // Issued at (timestamp)
  "exp": 1715667300          // Expiration (timestamp)
}
```

**Why not include permissions in token?**
- Permissions can change → Token-based copy becomes stale
- Cache misses occur for new permissions → Need live check
- Token stays same for 15 min, but permissions can change instantly
- Solution: Cache-backed permission lookup in filter

### Security Properties

| Property | Implementation | Benefit |
|----------|----------------|---------|
| **Signing** | HS256 (HMAC-SHA256) | Cannot be forged without secret |
| **Expiration** | 15 minutes | Limits compromise window |
| **HttpOnly cookies** | `Secure, SameSite=Lax` | Cannot be accessed by JavaScript (XSS mitigation) |
| **Tenant isolation** | tenantId in token | Multi-tenant data separation |
| **Token version** | Instant revocation | Password changes immediately invalidate all tokens |
| **Refresh rotation** | Database tracking | Compromise detection via revocation chain |

---

## Cache Strategy

### Permission Cache

```
Key: "perms:{schemaName}:{userId}"
Value: Set<String> ["BILLING_MANAGE", "CUSTOMER_MANAGE", "PRODUCT_MANAGE"]
TTL: 15 minutes

Workflow:
  1. Filter checks permission in cache
  2. Cache hit (90%) → O(1) in-memory lookup
  3. Cache miss → Query DB, populate cache
  4. Updated permissions → Cache invalidated immediately
```

### Token Version Cache

```
Key: "token_version:{tenantId}:{userId}"
Value: 5
TTL: 1 hour

Workflow:
  1. Filter validates token version from cache
  2. Password changes → Cache DELETE
  3. Next request → Cache MISS → Fetch from DB
  4. User.tokenVersion incremented → Cache populated
```

### Schema Cache

```
Key: "schema:{tenantId}"
Value: "s_456"
TTL: 1 hour

Workflow:
  1. Filter resolves schema name from tenantId
  2. Schema remains stable → Cache HIT (99%)
  3. Tenant recreates schema → Cache invalidation
```

---

## Token Lifecycle Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    TOKEN LIFECYCLE                              │
└─────────────────────────────────────────────────────────────────┘

LOGIN
  ├─ POST /api/auth/login
  │   ├─ Validate credentials
  │   ├─ Fetch User.tokenVersion (= 0)
  │   ├─ Generate JWT (tokenVersion=0, exp=now+15min)
  │   ├─ Generate RefreshToken (exp=now+7days)
  │   └─ Response: Set cookies
  │
  └─ Cookies set:
      ├─ accessToken (HttpOnly, Secure, SameSite=Lax, path=/api, 15min)
      └─ refreshToken (HttpOnly, Secure, SameSite=Lax, path=/api/auth/refresh, 7days)

AUTHENTICATED REQUEST (0-15 min)
  ├─ GET /api/v1/subscriptions/current
  │   └─ JwtAuthenticationFilter:
  │       ├─ Extract JWT from cookie
  │       ├─ Validate signature
  │       ├─ Check expiration
  │       ├─ Validate tokenVersion (cache lookup)
  │       ├─ Load permissions (cache lookup)
  │       └─ Create SecurityContext
  │
  └─ Controller processes request

TOKEN REFRESH (0-7 days)
  ├─ POST /api/auth/refresh
  │   ├─ JwtAuthenticationFilter:
  │   │   └─ Extract refreshToken from cookie
  │   │
  │   ├─ AuthService.refresh():
  │   │   ├─ Validate refreshToken (not revoked, not expired)
  │   │   ├─ Generate new access JWT (15min)
  │   │   ├─ Rotate refresh token:
  │   │   │   ├─ Mark old token revoked in DB
  │   │   │   ├─ Generate new token
  │   │   │   └─ Set new expiry (now+7days)
  │   │   └─ Response: Set new cookies
  │   │
  │   └─ Cookies updated:
  │       ├─ accessToken (new)
  │       └─ refreshToken (new)
  │
  └─ Session continues for 7 more days

PASSWORD CHANGE (anytime)
  ├─ PATCH /api/auth/profile/password
  │   ├─ Validate old password
  │   ├─ Hash new password
  │   ├─ Update User:
  │   │   ├─ Set password_hash
  │   │   ├─ Increment tokenVersion++ (was 0, now 1)
  │   │   └─ Save
  │   │
  │   ├─ Clear token version cache
  │   │   └─ Redis DELETE "token_version:*:userId"
  │   │
  │   └─ All old tokens INVALID (tokenVersion mismatch)
  │
  └─ User must login again with new password

TOKEN EXPIRATION (after 15 min OR 7 days)
  ├─ Expired access token:
  │   ├─ Filter detects: NOW > exp
  │   ├─ Validation fails
  │   └─ Return 401 UNAUTHORIZED
  │   ├─ Client calls POST /api/auth/refresh
  │   │   └─ Uses refreshToken (if not expired)
  │   │
  │   └─ If refreshToken also expired:
  │       └─ User must login again
  │
  └─ Expired refresh token:
      ├─ Filter validates: NOW > expiry_date
      ├─ Reject: TOKEN_EXPIRED
      └─ User must login again

LOGOUT
  ├─ POST /api/auth/logout
  │   ├─ Extract refreshToken
  │   ├─ RefreshTokenService.revokeToken(token):
  │   │   └─ Mark token revoked in DB
  │   │
  │   └─ Response:
  │       └─ Clear cookies (set MaxAge=0)
  │
  └─ All subsequent requests:
      ├─ No accessToken in cookie
      ├─ No Authorization header
      └─ Filter chain continues (Unauthenticated)
```

---

## Error Scenarios

### Scenario 1: Token Tampered

```
Original JWT: eyJhbGc...{claims}...signature
Attacker modifies: change tenantId from 456 to 999

JwtTokenProvider.validateToken():
  1. Parse JWT
  2. Calculate signature: HMAC(header.payload, secret)
  3. Compare with provided signature
  4. FAIL: Signatures don't match
  5. Throw JwtException
  6. Filter returns 401 UNAUTHORIZED
```

**Protection:** Signature verification — can't be forged without secret

### Scenario 2: Token from Another Tenant

```
User A (tenantId=456) steals User B's token (tenantId=789)

JwtAuthenticationFilter:
  1. Extract token
  2. Validate signature ✓ (signature is valid)
  3. Extract claims:
     └─ tenantId = 789
  4. TenantContext.set(789)
  5. Load User A's row from Schema 789
     └─ Result: User not found OR wrong user
     └─ Query fails, controller rejects

Or if User A has ID=123 in schema 789:
  1. Extract userId=123 from token
  2. TenantContext.set(789)
  3. Load User 123 from schema 789
  4. Load permissions for User 123/schema 789
  5. Proceed with THEIR permissions (cross-access prevented)
```

**Protection:** TenantContext isolation — can't access data from wrong tenant

### Scenario 3: Compromised Token After Password Change

```
Time: 14:00 - User logs in, gets token.tokenVersion=5
Time: 14:10 - Attacker steals token
Time: 14:15 - User changes password:
  ├─ User.tokenVersion incremented (was 5, now 6)
  └─ Cache invalidated

Time: 14:20 - Attacker uses stolen token:
  Filter validates:
    1. Signature ✓ (valid)
    2. Expiration ✓ (not expired)
    3. Token version check:
       ├─ Cached version lookup: cache miss
       ├─ Fetch from DB: User.tokenVersion = 6
       ├─ Token claims tokenVersion = 5
       ├─ 5 ≠ 6
       └─ REJECT: TOKEN_REVOKED (401)

Attacker cannot use the token
```

**Protection:** Token version mismatch detection — immediate revocation

### Scenario 4: Replay Attack (Stolen Refresh Token)

```
Time: 14:00 - User logs in, gets refreshToken=UUID-111
Time: 14:10 - Attacker intercepts and steals UUID-111
Time: 14:15 - User calls POST /refresh:
  ├─ Valid UUID-111
  ├─ Validate: not revoked, not expired ✓
  ├─ Mark UUID-111: revoked=true
  ├─ Generate new UUID-222
  └─ Response: new cookie with UUID-222

Time: 14:20 - Attacker tries to use stolen UUID-111:
  RefreshTokenService.validateRefreshToken():
    1. Lookup UUID-111 in DB
    2. Check: revoked = true
    3. REJECT: TOKEN_REVOKED (401)

Attacker cannot use the old token
Alert: Possible compromise
```

**Protection:** Refresh token rotation + revocation tracking

---

## Best Practices

### For Frontend

✅ **DO:**
- Store tokens in HttpOnly cookies (automatically set by server)
- Never access tokens in JavaScript
- Use `withCredentials: true` in axios/fetch
- Implement automatic token refresh on 401

❌ **DON'T:**
- Store tokens in localStorage (XSS vulnerable)
- Send tokens in URL params
- Decode JWT client-side for security checks (only UI hints)

### For Backend

✅ **DO:**
- Validate token signature on every request
- Check token expiration strictly
- Implement token version checking for revocation
- Rotate refresh tokens on each use
- Clear TenantContext in finally blocks
- Log token validation failures

❌ **DON'T:**
- Trust JWT claims without signature validation
- Store sensitive data in JWT (it's readable)
- Use weak secrets (< 256 bits)
- Forget to set HttpOnly on cookies

---

## Troubleshooting

### "TOKEN_REVOKED" (401)

**Causes:**
1. ✓ Password was changed (token version mismatch)
2. ✓ User was deleted
3. ✓ Refresh token was rotated
4. ✓ Session was logged out
5. ✓ Admin revoked tokens

**Solution:** User must login again

### "TOKEN_EXPIRED" (401)

**Causes:**
1. ✓ Access token expired (> 15 min old)
2. ✓ Refresh token expired (> 7 days old)

**Solution:**
- If access token: Refresh (POST /api/auth/refresh)
- If refresh token: Login again

### "TOKEN_INVALID" or signature verification fails

**Causes:**
1. ✓ JWT was tampered with
2. ✓ Token was generated with different secret
3. ✓ Malformed JWT

**Solution:** User must login again

### "UNAUTHORIZED" (no token)

**Causes:**
1. ✓ No accessToken cookie present
2. ✓ No Authorization header
3. ✓ Cookies not sent (withCredentials: false in client)

**Solution:**
- Client: Check withCredentials in axios/fetch
- Browser: Check cookie settings (HttpOnly, Secure, SameSite)

---

## Summary

| Component | Lifetime | Validation | Revocation |
|-----------|----------|------------|-----------|
| **Access Token** | 15 min | Signature + expiry + version | Immediate (tokenVersion change) |
| **Refresh Token** | 7 days | Database lookup + expiry | Manual revocation + rotation |
| **Token Version** | ∞ | Cache + DB | Increment on password change |
| **Permission Cache** | 15 min | In-memory | Invalidate on role change |

**Key insight:** Version field enables stateless, immediate revocation without requiring token blacklists.

