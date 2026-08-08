---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - request-lifecycle
  - filters
  - security
  - multitenancy
  - logging
  - transactions
related_documents:
  - ./system-design.md
  - ../02-multi-tenancy/tenant-context-lifecycle.md
  - ../03-security/authentication.md
---

# Request Flow

## Executive Summary

An HTTP request flows through 5 layers: **Tomcat → MdcLoggingFilter → MdcSecurityEnrichmentFilter → JwtAuthenticationFilter → Endpoint → GlobalExceptionHandler → Response**. Each layer serves a specific purpose: request ID tracking, security context setup, JWT validation + TenantContext initialization, business logic execution, and error handling. TenantContext is set by the JWT filter and cleared in its finally block, ensuring tenant isolation. Transactions are scoped to the endpoint method; the database connection automatically routes to the correct tenant schema. Tracing IDs (requestId, traceId, spanId) are added to MDC (logging context) and propagated to response headers for distributed tracing. This document traces a single request end-to-end.

---

## Context / Problem

### Why Multiple Filters?

Each filter has a single responsibility:
- **MdcLoggingFilter** — Request/response tracking (timing, IDs, IP)
- **MdcSecurityEnrichmentFilter** — Enrich logs with security context (after Spring Security is done)
- **JwtAuthenticationFilter** — JWT validation + TenantContext setup (where tenant isolation happens)

Order matters: MdcLoggingFilter runs first (must set requestId before everything), JwtAuthenticationFilter runs last (Spring Security needs it).

### Why MDC?

Mapped Diagnostic Context (SLF4J/Logback) allows log lines to include context (tenantId, userId, requestId) without passing it as a parameter in every method call. When a background thread reads logs or traces an error, it knows:
- Which request is this from? (requestId)
- Which tenant? (tenantId)
- Which user? (userId)
- Which span in distributed trace? (spanId)

### Why GlobalExceptionHandler?

Centralized error handling converts exceptions to consistent JSON responses:
- `ValidationException` → HTTP 400 + validation error fields
- `AccessDeniedException` → HTTP 403
- `TenantException.notFound()` → HTTP 404
- Unhandled exceptions → HTTP 500

Without it, exceptions would propagate as HTTP 500 with stack traces exposed to frontend.

---

## Dependencies

### Inbound (HTTP Request)

Originates from client (browser, mobile app, API consumer) over HTTP/HTTPS.

### Outbound (What Each Layer Calls)

- **MdcLoggingFilter** → `UUID.randomUUID()` — Generate IDs
- **MdcLoggingFilter** → `System.currentTimeMillis()` — Measure duration
- **MdcLoggingFilter** → `MDC.put()` — Store in thread-local context
- **JwtAuthenticationFilter** → `JwtTokenProvider.validateToken()` — Verify JWT
- **JwtAuthenticationFilter** → `TenantContext.setTenantId()` / `setCurrentSchema()` — Set tenant
- **JwtAuthenticationFilter** → `SchemaCacheService.resolveSchemaName()` — Lookup schema
- **JwtAuthenticationFilter** → `PermissionCacheService.getPermissions()` — Fetch authorities
- **Endpoint** → `@Transactional` Spring transaction management — Manages connection pool + commit/rollback
- **Endpoint** → `SecurityContextHolder.getAuthentication()` — Read current user
- **GlobalExceptionHandler** → Exception handlers — Convert exceptions to JSON

### Configuration
- `logging.level.root: INFO` — Log level
- `spring.mvc.throw-exception-if-no-handler-found: true` — Return 404 for unknown endpoints
- `spring.resources.add-mappings: false` — Disable static resource serving

---

## Design / Implementation

### Layer 1: HTTP Server (Tomcat)

Tomcat receives HTTP request, creates `HttpServletRequest` and `HttpServletResponse` objects, routes to ServletFilterChain.

```
GET /api/v1/billing/invoices?page=0&size=10
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Accept: application/json
User-Agent: Mozilla/5.0
```

### Layer 2: MdcLoggingFilter (Earliest)

**Purpose**: Assign request IDs and set up logging context.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)  // Run first
public class MdcLoggingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Generate or extract request ID
            String requestId = httpRequest.getHeader("X-Request-Id");
            if (requestId == null) requestId = UUID.randomUUID().substring(0, 12);
            MDC.put("requestId", requestId);
            httpResponse.setHeader("X-Request-Id", requestId);
            
            // Same for trace ID (distributed tracing)
            String traceId = httpRequest.getHeader("X-Trace-Id");
            if (traceId == null) traceId = UUID.randomUUID().substring(0, 16);
            MDC.put("traceId", traceId);
            
            // Span ID for this service
            String spanId = UUID.randomUUID().substring(0, 16);
            MDC.put("spanId", spanId);
            
            // Request metadata
            MDC.put("method", httpRequest.getMethod());
            MDC.put("uri", httpRequest.getRequestURI());
            MDC.put("clientIp", getClientIp(httpRequest));
            
            // Continue to next filter
            chain.doFilter(request, response);
            
        } finally {
            // After all processing, measure duration
            long duration = System.currentTimeMillis() - startTime;
            MDC.put("status", String.valueOf(httpResponse.getStatus()));
            MDC.put("durationMs", String.valueOf(duration));
            
            log.info("Request completed");  // Includes all MDC values
            MDC.clear();  // Clean up
        }
    }
}
```

**MDC at this point:**
```
requestId=abc123def456
traceId=xyz789...
spanId=span789...
method=GET
uri=/api/v1/billing/invoices
clientIp=192.168.1.100
```

**Log line example:**
```
2026-04-24T10:15:30.123Z [INFO] requestId=abc123 traceId=xyz789 spanId=span789 method=GET uri=/api/v1/billing/invoices clientIp=192.168.1.100 durationMs=245 status=200 -> Request completed
```

### Layer 3: Spring Security Filters (Multiple)

Spring Security applies filters for CSRF tokens, CORS, etc. (configured in `SecurityConfig`).

At this point: no user is authenticated yet.

### Layer 4: JwtAuthenticationFilter (Last Filter)

**Purpose**: Extract JWT, validate it, set up Spring Security context + TenantContext.

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) 
            throws ServletException, IOException {
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Step 1: Extract JWT from Authorization header or cookie
            String jwt = getJwtFromRequest(request);
            if (jwt == null) {
                log.debug("No JWT found");
                filterChain.doFilter(request, response);  // Unauthenticated request
                return;
            }
            
            // Step 2: Validate JWT signature + expiry
            if (!jwtTokenProvider.validateToken(jwt)) {
                log.warn("Invalid JWT signature");
                filterChain.doFilter(request, response);  // Skip auth
                return;
            }
            
            // Step 3: Parse JWT claims
            Claims claims = jwtTokenProvider.getAllClaimsFromToken(jwt);
            Boolean isSuperAdmin = claims.get("isSuperAdmin", Boolean.class);
            
            if (Boolean.TRUE.equals(isSuperAdmin)) {
                // Super admin path (no tenant context)
                UserPrincipal principal = new UserPrincipal(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    null, null, null, null, null,
                    List.of("ROLE_SUPER_ADMIN")
                );
                setSecurityContext(principal);
                filterChain.doFilter(request, response);
                return;
            }
            
            // Step 4: Tenant user path — extract context
            Long userId = jwtTokenProvider.getUserIdFromToken(jwt);
            Long tenantId = jwtTokenProvider.getTenantIdFromToken(jwt);
            Long roleId = jwtTokenProvider.getRoleIdFromToken(jwt);
            Long tokenVersion = jwtTokenProvider.getTokenVersionFromToken(jwt);
            
            // Step 5: Resolve schema name from Redis cache
            String schemaName = schemaCacheService.resolveSchemaName(tenantId);
            log.debug("Resolved schema: {} for tenantId: {}", schemaName, tenantId);
            
            // Step 6: Set TenantContext (ThreadLocal)
            TenantContext.setTenantId(tenantId);
            TenantContext.setCurrentSchema(schemaName);
            
            // Step 7: Validate token version (check if token was revoked)
            if (!tokenVersionCacheService.isTokenVersionValid(schemaName, userId, tokenVersion)) {
                log.warn("Token version revoked: userId={}, claimedVersion={}", userId, tokenVersion);
                sendUnauthorized(response, "TOKEN_REVOKED", "Token has been revoked");
                return;
            }
            
            // Step 8: Fetch user permissions from cache
            Set<String> permissions = permissionCacheService.getPermissions(schemaName, userId, roleId);
            
            // Step 9: Build UserPrincipal with authorities
            UserPrincipal principal = new UserPrincipal(
                userId, email, password, tenantId, schemaName, roleId, role,
                new ArrayList<>(permissions)
            );
            
            // Step 10: Set Spring Security context
            UsernamePasswordAuthenticationToken auth = 
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
            
            // Step 11: Continue to controller
            filterChain.doFilter(request, response);
            
        } finally {
            // CRITICAL: Clear TenantContext regardless of success/exception
            TenantContext.clear();
            log.debug("TenantContext cleared after request");
        }
    }
    
    private String getJwtFromRequest(HttpServletRequest request) {
        // Try Authorization header first
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // Fallback to cookie
        return cookieUtils.extractAccessToken(request).orElse(null);
    }
}
```

**State after JwtAuthenticationFilter:**
```
MDC: requestId, traceId, spanId, method, uri
ThreadLocal TenantContext: tenantId=456, schemaName="s_456"
Spring SecurityContextHolder: UserPrincipal(userId=123, tenantId=456, authorities=[TENANT_VIEW, BILLING_MANAGE])
```

### Layer 5: MdcSecurityEnrichmentFilter (After JWT Filter)

**Purpose**: Extract authenticated user info and add to MDC for logging.

```java
@Component
public class MdcSecurityEnrichmentFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        try {
            // SecurityContext is now populated by JwtAuthenticationFilter
            Long tenantId = SecurityUtils.getCurrentTenantId();
            Long userId = SecurityUtils.getCurrentUserId();
            String role = SecurityUtils.getCurrentRole();
            
            MDC.put("tenantId", tenantId != null ? tenantId.toString() : "-");
            MDC.put("userId", userId != null ? userId.toString() : "-");
            MDC.put("role", role != null ? role : "-");
            
        } catch (Exception e) {
            // If SecurityUtils fails, just continue (unauthenticated requests)
            MDC.put("tenantId", "-");
            MDC.put("userId", "-");
        }
        
        chain.doFilter(request, response);
    }
}
```

**MDC now complete:**
```
requestId=abc123
traceId=xyz789
spanId=span789
tenantId=456
userId=123
role=OWNER
method=GET
uri=/api/v1/billing/invoices
```

### Layer 6: Controller Endpoint

Request reaches controller method with Spring Security context available.

```java
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class InvoiceController {
    
    private final InvoiceService invoiceService;
    
    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")  // Permission check
    @Transactional(readOnly = true)  // Start transaction, route to tenant schema
    public ResponseEntity<ApiResponse<Page<InvoiceResponse>>> listInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        log.info("Listing invoices for page: {}", page);
        // At this point:
        // - TenantContext is set to tenantId=456, schemaName="s_456"
        // - SecurityContextHolder has UserPrincipal
        // - MDC has all context
        
        Page<InvoiceResponse> invoices = invoiceService.getInvoices(page, size);
        
        return ResponseEntity.ok(ApiResponse.success(invoices, "Invoices retrieved"));
    }
}
```

**What happens inside service:**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {
    
    private final InvoiceRepository invoiceRepository;
    
    @Transactional(readOnly = true)
    public Page<InvoiceResponse> getInvoices(int page, int size) {
        
        log.info("Query invoices with pagination");
        // Pageable from Spring Data
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        
        // Repository query
        Page<Invoice> invoices = invoiceRepository.findAll(pageable);
        
        // ← Hibernate intercepts query execution
        // ← Calls CurrentTenantIdentifierResolver.resolveCurrentTenantIdentifier()
        // ← Reads TenantContext.getSchemaName() → "s_456"
        // ← Executes: SET search_path TO "s_456", public;
        // ← Query runs ONLY on s_456.invoices (not public or s_457)
        
        return invoices.map(inv -> InvoiceResponse.builder()
            .id(inv.getId())
            .amount(inv.getAmount())
            .status(inv.getStatus())
            .build())
                .toList();
    }
}
```

**Actual SQL executed:**
```sql
SET search_path TO "s_456", public;
SELECT i.id, i.amount, i.status, i.created_at 
FROM invoices i 
WHERE i.deleted = false 
ORDER BY i.created_at DESC 
LIMIT 10 OFFSET 0;
```

### Layer 7: Response (Success Path)

Controller returns `ResponseEntity`:

```java
ResponseEntity.ok(
    ApiResponse.success(
        page(invoices),
        "Invoices retrieved"
    )
)
```

Spring converts to JSON:
```json
{
  "success": true,
  "data": {
    "content": [
      { "id": 100, "amount": 999.00, "status": "PAID" },
      { "id": 99, "amount": 500.00, "status": "DRAFT" }
    ],
    "totalElements": 2,
    "totalPages": 1,
    "currentPage": 0,
    "pageSize": 10
  },
  "message": "Invoices retrieved",
  "timestamp": "2026-04-24T10:15:30.245Z"
}
```

Response headers:
```
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-Id: abc123def456
X-Trace-Id: xyz789...
Set-Cookie: [if token refresh was needed]
```

### Layer 7b: Exception Path (Error Handling)

If an exception is thrown anywhere in the request, it propagates to `GlobalExceptionHandler`:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Object>> handleBaseException(BaseException ex) {
        log.error("Business exception: {} - {}", 
            ex.getErrorCode().getCode(), 
            ex.getMessage(),
            ex);  // Includes stack trace in logs, NOT in response
        
        return ResponseEntity
            .status(ex.getErrorCode().getHttpStatus())
            .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode().getCode()));
    }
    
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());  // Still in MDC context
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("Access denied", "AUTH_0003"));
    }
}
```

Example: User lacks `BILLING_MANAGE` permission:

```
@PreAuthorize("hasAuthority('BILLING_MANAGE')")  // Check fails
↓ Spring throws: AccessDeniedException
↓ Caught by GlobalExceptionHandler
↓ Logs: requestId=abc123 userId=123 tenantId=456 Access denied
↓ Returns:
```

```json
HTTP/1.1 403 Forbidden
{
  "success": false,
  "error": "Access denied",
  "errorCode": "AUTH_0003",
  "timestamp": "2026-04-24T10:15:30.250Z"
}
```

### Layer 8: Response Written

MdcLoggingFilter's finally block executes:

```java
finally {
    long duration = System.currentTimeMillis() - startTime;
    MDC.put("status", String.valueOf(httpResponse.getStatus()));  // 200 or 403
    MDC.put("durationMs", String.valueOf(duration));
    log.info("Request completed");  // Includes all MDC + status + duration
    MDC.clear();  // Clean up for next request in thread pool
}
```

**Final log line:**
```
2026-04-24T10:15:30.250Z [INFO] requestId=abc123 traceId=xyz789 spanId=span789 tenantId=456 userId=123 role=OWNER method=GET uri=/api/v1/billing/invoices status=200 durationMs=127 -> Request completed
```

HTTP response sent to client with 200 or 403 status + JSON body + request/trace ID headers.

---

## Flow

See Layer-by-Layer ASCII diagrams above.

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `MdcLoggingFilter` | [APP-8] | `doFilter()` | Generate request ID, trace ID, set MDC; measure timing |
| `MdcLoggingFilter` | [APP-8] | finally block | Finalize MDC with status + duration; clear for next request |
| `MdcSecurityEnrichmentFilter` | [APP-9] | `doFilter()` | Extract authenticated user info; enrich MDC |
| `JwtAuthenticationFilter` | [AUTH-30] | `doFilterInternal()` | JWT validation, TenantContext setup, Spring Security |
| `JwtAuthenticationFilter` | [AUTH-30] | finally block | Clear TenantContext (critical for isolation) |
| `GlobalExceptionHandler` | [APP-10] | `handleBaseException()` | Convert BaseException to HTTP response |
| `GlobalExceptionHandler` | [APP-10] | `handleValidationException()` | Convert validation errors to 400 + field errors |
| `GlobalExceptionHandler` | [APP-10] | `handleAccessDeniedException()` | Convert permission checks to 403 |
| `Controller` | varies | `doFilter() + chain` | Handle endpoint logic + transactions |

---

## Rules / Constraints

1. **Filters MUST run in a fixed order:** MdcLoggingFilter → JwtAuthenticationFilter → MdcSecurityEnrichmentFilter (or other Spring Security filters) → Endpoint. Use `@Order(Ordered.HIGHEST_PRECEDENCE)` and lower values for earlier execution.

2. **TenantContext MUST be cleared in JwtAuthenticationFilter's finally block** — Every request, regardless of success/exception. If a request ends with an exception, the finally block STILL executes, ensuring cleanup.

3. **MDC.clear() MUST be called after response** — Otherwise the next request in the same Tomcat worker thread inherits the previous request's MDC values, polluting logs.

4. **All business logic MUST be called from @Transactional endpoints or services** — The transaction boundary starts HERE, after TenantContext is set. Schemas must be resolved BEFORE @Transactional (in the filter).

5. **@PreAuthorize() annotations MUST be on controller methods, not services** — Spring Security checks authorities BEFORE calling the method. If check fails, exception is thrown and endpoint is never invoked.

6. **Response headers MUST include X-Request-Id and X-Trace-Id** — For client-side tracing and incident response. Client should echo these IDs in subsequent requests for correlation.

7. **Stack traces MUST NOT be included in JSON error responses** — Security risk (exposes internal code structure). Log stack trace server-side; return user-friendly message to client.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| JWT missing or malformed | (No exception; request continues unauthenticated) | 401 (from @PreAuthorize) | Client sends valid JWT in Authorization header |
| JWT expired | `JwtException` | 401 Unauthorized | Client calls POST /api/auth/refresh to get new token |
| TenantContext not cleared from previous request | Silent bug (query routes to wrong schema) | Query succeeds but returns data from wrong tenant | Always test exception paths; enforce finally block |
| @PreAuthorize check fails (missing permission) | `AccessDeniedException` | 403 Forbidden | User role/permissions must include required authority |
| Validation error (@Valid fails) | `MethodArgumentNotValidException` | 400 Bad Request | Client corrects input; example: invalid email format |
| Schema does not exist | `PGSQLException` | 500 Internal Server Error | Tenant schema provisioning incomplete; manual recovery needed |
| Controller method throws unhandled exception | Any `RuntimeException` | 500 Internal Server Error | GlobalExceptionHandler catches and logs; client sees generic 500 |
| Database connection unavailable | `JdbcException or DataAccessException` | 500 Internal Server Error | Database is down; retry after recovery |
| Request takes >30 seconds (timeout) | `SocketTimeoutException` | 504 Gateway Timeout (if proxy) or connection reset | Long-running operations should be async; client retries |

---

## Edge Cases

- **Concurrency**: Multiple simultaneous requests on different Tomcat threads have separate MDC + TenantContext. No collision because ThreadLocal is per-thread.

- **Timezone**: All timestamps in logs are in UTC (from `Instant.now()` or PostgreSQL `TIMESTAMPTZ`). No timezone bugs. Server can be in any timezone; ISO 8601 format makes it explicit.

- **Tenant Isolation**: If a user's JWT includes `tenantId=456` but the request reaches TenantContext with `tenantId=457` (should never happen if JWT is valid), the query hits s_457's tables. Headers X-Request-Id and MDC logs allow tracing the source of the wrong token.

- **Exception during TenantContext setup**: If `schemaCacheService.resolveSchemaName()` throws an exception, JwtAuthenticationFilter's finally block still runs and clears TenantContext (even though it was not fully set). No harm.

- **RequestId header collision**: If client sends `X-Request-Id: abc123` and another client sends the same ID simultaneously (rare), logs will show both requests with same ID. Solution: UUID.randomUUID() is cryptographically unique; collision probability is negligible.

- **Missing JWT on public endpoints** (e.g., POST /api/auth/signup, GET /api/plans): JwtAuthenticationFilter sees no JWT, skips auth, allows request to proceed unauthenticated. No error. Controller can check `SecurityContextHolder.getAuthentication()` to see if user is authenticated.

---

## Known Issues / Limitations

1. **MDC does not propagate to async threads (by default)** — If a service spawns a thread pool task (@Async), the task does NOT inherit parent MDC. Logs from the async task miss requestId, tenantId. Workaround: Wrap async tasks in ExecutorService with MDC context copying.

2. **TenantContext does not propagate to async threads** — Same issue. Async tasks must be passed tenantId explicitly or wrapped in context. No automatic propagation.

3. **GlobalExceptionHandler does NOT log request body for debug** — If validation fails, the input JSON is not logged. Helpful for debugging but verbose. Solution: Add custom instrumentation if needed.

4. **No timeout on MdcLoggingFilter** — If a request hangs for hours, MdcLoggingFilter's finally block never runs. MDC values persist in thread pool until thread dies. This is expected (Tomcat manages thread lifecycle).

5. **X-Request-Id header collision possible but negligible** — If client generates its own ID (instead of letting filter generate), and two clients send the same ID, the filter doesn't detect it. Recommendation: Always let filter generate IDs; if client provides one and collision occurs, use the provided one anyway.

---

## Future Improvements

1. Add request/response body logging (with sanitization) — Log request JSON and response JSON for debugging, but redact sensitive fields (passwords, tokens). Use aspect or servlet wrapper.

2. Implement distributed tracing with OpenTelemetry — Export traces to Jaeger or Tempo for visualization. Currently only MDC logging is used.

3. Add rate limiting at filter level — Check IP rate limit before JWT validation; reject at 429 status if rate exceeded.

4. Implement request circuit breaker — If server is overwhelmed (queue size >1000), return 503 Service Unavailable for new requests.

5. Add custom header validation — Check for required headers (e.g., `X-Api-Version`) and return 400 if missing.

---

## Related Documents
- [authentication.md](../03-security/authentication.md) — JWT validation + UserPrincipal creation (JwtAuthenticationFilter)
- [tenant-context-lifecycle.md](../02-multi-tenancy/tenant-context-lifecycle.md) — ThreadLocal TenantContext lifecycle
- [authorization-rbac.md](../03-security/authorization-rbac.md) — @PreAuthorize() permission checks
- [multi-tenancy-strategy.md](../02-multi-tenancy/multi-tenancy-strategy.md) — Schema routing to correct tenant database
- [error-handling.md](../06-api/error-handling.md) — GlobalExceptionHandler detail + error codes
