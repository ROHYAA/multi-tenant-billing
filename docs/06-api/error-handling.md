---
version: 1.0
date: 2026-04-25
author: Manoj Pandi
status: Production Ready
tags:
  - api
  - error-handling
  - exceptions
  - http-status
  - validation
related_documents:
  - ../01-architecture/request-flow.md
  - ../02-multi-tenancy/cross-tenant-safety.md
  - ../06-api/auth-api.md
---

# Error Handling

## Executive Summary

MTBS implements enterprise-grade error handling via a centralized exception hierarchy (`BaseException` with subclasses) and `GlobalExceptionHandler` that converts exceptions to standardized `ApiResponse` JSON with embedded error codes and HTTP status codes. All exceptions are **unchecked** (extend `RuntimeException`), enabling clean service layer code without `throws` clauses. Error responses include tenant context in MDC logs for audit trails. Understanding error handling is **critical for debugging production issues and implementing reliable client-side retry logic**.

---

## Context & Problem

### Why Centralized Error Handling Matters

Without a standard error handling approach, code becomes fragmented:

1. **Inconsistent HTTP status codes** — Same error returns 400 from one endpoint, 500 from another
2. **Non-standard JSON format** — Clients can't parse errors consistently
3. **Audit loss** — Errors logged without tenant context; impossible to diagnose tenant-specific issues
4. **Weak retry logic** — Clients can't distinguish recoverable (429, 503) from permanent (400, 403) errors
5. **Security leaks** — Stack traces exposed; internal implementation details leaked to attackers

### Spring Security Exception Complexity

Spring Security throws `AccessDeniedException`, `AuthenticationException`, and others outside controller scope. MTBS bridges this gap:

```
JwtAuthenticationFilter
  ├─ Extracts JWT claims
  ├─ Throws AuthException.tokenExpired()
  │   ↓ (caught by GlobalExceptionHandler)
  └─ → 401 Unauthorized response
```


## Dependencies

### Inbound (Who throws exceptions)

- **Service layer** — `SubscriptionService.upgradeSubscription()` throws `SubscriptionException.upgradePending()`
- **Repository layer** — JPA `findById()` wrapped in `ResourceException.notFound()`
- **Security layer** — `JwtAuthenticationFilter` throws `AuthException.tokenExpired()`
- **Validation** — JSR-303 `@NotNull`, `@Email` trigger `MethodArgumentNotValidException`
- **Third-party APIs** — `RazorpayException` caught and wrapped as `PaymentException.razorpayError()`

### Outbound (What catches exceptions)

- `GlobalExceptionHandler` — `@RestControllerAdvice` catches all exceptions
- `MdcLoggingFilter` — Extracts error details from MDC (tenantId, userId)
- **Client code** — Frontend reads `error.errorCode` and `error.success` to determine retry strategy

### Configuration

**application.yaml:**
```yaml
server:
  error:
    include-message: always        # Include error message in response
    include-binding-errors: always # Include validation field errors
    include-stacktrace: never      # Never expose stack trace (security)

spring:
  mvc:
    throw-exception-if-no-handler-found: true  # 404 explicitly, not 500
    dispatch-options-request: true

logging:
  level:
    com.mtbs.app.exception: DEBUG  # Log exception handler decisions
```

---

## Design & Implementation

### Exception Hierarchy

```
RuntimeException
  └── BaseException (abstract)
       ├── AuthException
       ├── AuthenticationException (Spring Security)      
       ├── ResourceException
       ├── PaymentException
       ├── SubscriptionException
       ├── TenantException
       └── TokenException
```

**Why unchecked?** Checked exceptions pollute service method signatures and encourage silencing via `catch(Exception e) {}`. Unchecked exceptions propagate cleanly to `GlobalExceptionHandler`.


### BaseException

```java
@Getter
public abstract class BaseException extends RuntimeException {
    
    private final ErrorCode errorCode;      // Structured error identifier
    private final String detail;             // Additional context
    
    protected BaseException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + (detail != null ? ": " + detail : ""));
        this.errorCode = errorCode;
        this.detail = detail;
    }
    
    protected BaseException(ErrorCode errorCode) {
        this(errorCode, null);
    }
}
```

**Design decisions:**

- `errorCode` — Immutable, maps to HTTP status via ErrorCode enum
- `detail` — Optional context; appended to exception message
- Message combines ErrorCode.message + detail for logging clarity


### ErrorCode Enum

```java
@Getter
public enum ErrorCode {
    
    // Auth errors (1000-1999)
    AUTH_INVALID_CREDENTIALS("AUTH_1001", "Invalid credentials", HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED("AUTH_1002", "Token has expired", HttpStatus.UNAUTHORIZED),
    AUTH_ACCESS_DENIED("AUTH_1003", "Access denied", HttpStatus.FORBIDDEN),
    AUTH_ACCOUNT_LOCKED("AUTH_1004", "Account is locked", HttpStatus.FORBIDDEN),
    AUTH_ACCOUNT_DISABLED("AUTH_1005", "Account is disabled", HttpStatus.FORBIDDEN),
    AUTH_EMAIL_ALREADY_EXISTS("AUTH_1006", "Email already exists", HttpStatus.CONFLICT),
    AUTH_RESET_TOKEN_INVALID("AUTH_1007", "Password reset token is invalid", HttpStatus.BAD_REQUEST),
    AUTH_RESET_TOKEN_EXPIRED("AUTH_1008", "Password reset token has expired", HttpStatus.BAD_REQUEST),
    AUTH_TOO_MANY_REQUESTS("AUTH_1009", "Too many failed login attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS),
    
    // Tenant errors (2000-2999)
    TENANT_NOT_FOUND("TNT_2001", "Tenant not found", HttpStatus.NOT_FOUND),
    TENANT_ALREADY_EXISTS("TNT_2002", "Tenant already exists", HttpStatus.CONFLICT),
    TENANT_SCHEMA_ERROR("TNT_2003", "Tenant schema error", HttpStatus.INTERNAL_SERVER_ERROR),
    TENANT_SUSPENDED("TNT_2004", "Tenant is suspended", HttpStatus.FORBIDDEN),
    TENANT_SLUG_ALREADY_EXISTS("TNT_2005", "Tenant slug already taken", HttpStatus.CONFLICT),
    TENANT_NOT_IN_ONBOARDING("TNT_2006", "Tenant is not in onboarding state", HttpStatus.BAD_REQUEST),
    ONBOARDING_STEP_OUT_OF_ORDER("TNT_2007", "Onboarding step must be completed in order", HttpStatus.BAD_REQUEST),
    
    // Token errors (3000-3999)
    TOKEN_INVALID("TKN_3001", "Invalid token", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("TKN_3002", "Token has expired", HttpStatus.UNAUTHORIZED),
    TOKEN_REVOKED("TKN_3003", "Token has been revoked", HttpStatus.UNAUTHORIZED),
    
    // Resource errors (4000-4999)
    RESOURCE_NOT_FOUND("RES_4001", "Resource not found", HttpStatus.NOT_FOUND),
    RESOURCE_ALREADY_EXISTS("RES_4002", "Resource already exists", HttpStatus.CONFLICT),
    RESOURCE_ACCESS_DENIED("RES_4003", "Access to resource denied", HttpStatus.FORBIDDEN),
    RESOURCE_INVALID("RES_4004", "Invalid resource", HttpStatus.BAD_REQUEST),
    PLAN_LIMIT_EXCEEDED("RES_4005", "Plan limit exceeded", HttpStatus.PAYMENT_REQUIRED),
    
    // Payment errors (5000-5999)
    PAYMENT_FAILED("PAY_5001", "Payment processing failed", HttpStatus.PAYMENT_REQUIRED),
    PAYMENT_ALREADY_PROCESSED("PAY_5002", "Payment has already been processed", HttpStatus.CONFLICT),
    INVALID_PAYMENT_METHOD("PAY_5003", "Invalid payment method", HttpStatus.BAD_REQUEST),
    RAZORPAY_ERROR("PAY_5004", "Razorpay API error", HttpStatus.BAD_GATEWAY),
    INVALID_PAYMENT_SIGNATURE("PAY_5005", "Payment signature verification failed", HttpStatus.BAD_REQUEST),
    ORDER_CREATION_FAILED("PAY_5006", "Failed to create payment order", HttpStatus.INTERNAL_SERVER_ERROR),
    
    // Subscription errors (7000-7999)
    SUBSCRIPTION_NOT_FOUND("SUB_7001", "No active subscription found", HttpStatus.NOT_FOUND),
    SUBSCRIPTION_UPGRADE_PENDING("SUB_7002", "An upgrade is already in progress", HttpStatus.CONFLICT),
    SUBSCRIPTION_INVALID_TRANSITION("SUB_7003", "Plan change not allowed from current state", HttpStatus.BAD_REQUEST),
    SUBSCRIPTION_ALREADY_CANCELLED("SUB_7004", "Subscription scheduled for cancellation", HttpStatus.CONFLICT),
    SUBSCRIPTION_NOT_CANCELLABLE("SUB_7005", "Only ACTIVE/TRIALING can be cancelled", HttpStatus.BAD_REQUEST),
    
    // Validation errors (6000-6999)
    VALIDATION_ERROR("VAL_6001", "Validation error", HttpStatus.BAD_REQUEST),
    INVALID_FORMAT("VAL_6002", "Invalid format", HttpStatus.BAD_REQUEST),
    MISSING_REQUIRED_FIELD("VAL_6003", "Missing required field", HttpStatus.BAD_REQUEST),
    
    // General (9000-9999)
    INTERNAL_ERROR("GEN_9001", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
    
    private final String code;           // e.g., "AUTH_1001"
    private final String message;        // e.g., "Invalid credentials"
    private final HttpStatus httpStatus; // e.g., HttpStatus.UNAUTHORIZED
    
    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
```

**Error code ranges:**

- 1xxx — Authentication & authorization
- 2xxx — Tenant/onboarding
- 3xxx — Token lifecycle
- 4xxx — Resource access
- 5xxx — Payment processing
- 6xxx — Validation
- 7xxx — Subscription business logic
- 9xxx — System errors


### Exception Subclasses

#### AuthException

```java
public class AuthException extends BaseException {
    
    public static AuthException invalidCredentials() {
        return new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    
    public static AuthException emailAlreadyExists(String email) {
        return new AuthException(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS, 
            "Email already exists: " + email);
    }
    
    public static AuthException tooManyRequests(long retryAfterSeconds) {
        return new AuthException(ErrorCode.AUTH_TOO_MANY_REQUESTS,
            "Try again in " + retryAfterSeconds + " seconds");
    }
}
```

**Thrown by:** AuthService, security filters


#### SubscriptionException

```java
public class SubscriptionException extends BaseException {
    
    public static SubscriptionException notFound(Long subscriptionId) {
        return new SubscriptionException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
            "ID: " + subscriptionId);
    }
    
    public static SubscriptionException upgradePending() {
        return new SubscriptionException(ErrorCode.SUBSCRIPTION_UPGRADE_PENDING);
    }
    
    public static SubscriptionException invalidTransition(String from, String to) {
        return new SubscriptionException(ErrorCode.SUBSCRIPTION_INVALID_TRANSITION,
            "Cannot transition from " + from + " to " + to);
    }
}
```

**Thrown by:** SubscriptionService


#### PaymentException

```java
public class PaymentException extends BaseException {
    
    public static PaymentException razorpayError(String code, String message) {
        return new PaymentException(ErrorCode.RAZORPAY_ERROR, 
            code + ": " + message);
    }
    
    public static PaymentException invalidSignature() {
        return new PaymentException(ErrorCode.INVALID_PAYMENT_SIGNATURE,
            "HMAC-SHA256 signature verification failed");
    }
}
```

**Thrown by:** PaymentService, RazorpayWebhookController


#### ResourceException

```java
public class ResourceException extends BaseException {
    
    public static ResourceException notFound(String resource, Long id) {
        return new ResourceException(ErrorCode.RESOURCE_NOT_FOUND,
            resource + " with ID: " + id);
    }
    
    public static ResourceException planLimitExceeded(String metric, long limit, long current) {
        return new ResourceException(ErrorCode.PLAN_LIMIT_EXCEEDED,
            metric + " - limit: " + limit + ", current: " + current);
    }
}
```

**Thrown by:** Service methods on object not found or access denied


---

### GlobalExceptionHandler

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    // ── Business logic exceptions ────────────────────────────────────────────
    
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Object>> handleBaseException(BaseException ex) {
        log.error("Business exception: {} - {}",
                ex.getErrorCode().getCode(), ex.getMessage(), ex);
        
        return ResponseEntity
                .status(ex.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(
                    ex.getMessage(),
                    ex.getErrorCode().getCode()));
    }
    
    // ── Validation errors ────────────────────────────────────────────────────
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex) {
        
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            fieldErrors.put(field, error.getDefaultMessage());
        });
        
        log.warn("Validation error: {}", fieldErrors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.validationError(
                    "Validation failed",
                    ErrorCode.VALIDATION_ERROR.getCode(),
                    fieldErrors));
    }
    
    // ── Spring Security exceptions ───────────────────────────────────────────
    
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDeniedException(
            AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                    "Access denied",
                    ErrorCode.AUTH_ACCESS_DENIED.getCode()));
    }
    
    // ── Third-party API exceptions ───────────────────────────────────────────
    
    @ExceptionHandler(RazorpayException.class)
    public ResponseEntity<ApiResponse<Object>> handleRazorpayException(
            RazorpayException ex) {
        log.error("Razorpay API error: {}", ex.getMessage());
        PaymentException paymentEx = PaymentException.razorpayError(
            "RAZORPAY_API", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(
                    paymentEx.getMessage(),
                    paymentEx.getErrorCode().getCode()));
    }
    
    // ── Request format exceptions ────────────────────────────────────────────
    
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        log.warn("Request not readable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                    "Invalid request format",
                    ErrorCode.INVALID_FORMAT.getCode()));
    }
    
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch: parameter={}, value={}, type={}",
                ex.getName(), ex.getValue(), ex.getRequiredType().getSimpleName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                    "Invalid type for " + ex.getName(),
                    ErrorCode.INVALID_FORMAT.getCode()));
    }
    
    // ── Catch-all ────────────────────────────────────────────────────────────
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                    "An unexpected error occurred",
                    ErrorCode.INTERNAL_ERROR.getCode()));
    }
}
```

**Handler ordering:** Spring applies handlers in specificity order. More specific (`BaseException`) is caught before generic (`Exception`).


### ApiResponse Format

```java
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    
    private boolean success;                    // true for 2xx, false for errors
    private String message;                     // User-friendly message
    private T data;                             // Response payload (if success)
    private String errorCode;                   // Error identifier (e.g., "AUTH_1001")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> fieldErrors;    // Validation errors by field
    private Instant timestamp;                  // Server timestamp (UTC)
    
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }
    
    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .errorCode(errorCode)
                .timestamp(Instant.now())
                .build();
    }
    
    public static <T> ApiResponse<T> validationError(
            String message,
            String errorCode,
            Map<String, String> fieldErrors) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .errorCode(errorCode)
                .fieldErrors(fieldErrors)
                .timestamp(Instant.now())
                .build();
    }
}
```

**JSON examples:**

Success response:
```json
{
  "success": true,
  "data": {"id": 123, "name": "Acme Inc"},
  "timestamp": "2026-04-25T10:30:00Z"
}
```

Error response:
```json
{
  "success": false,
  "message": "Payment signature verification failed",
  "errorCode": "PAY_5005",
  "timestamp": "2026-04-25T10:30:00Z"
}
```

Validation error response:
```json
{
  "success": false,
  "message": "Validation failed",
  "errorCode": "VAL_6001",
  "fieldErrors": {
    "email": "must be a valid email address",
    "password": "must be at least 8 characters"
  },
  "timestamp": "2026-04-25T10:30:00Z"
}
```

---

## HTTP Status Mapping

| ErrorCode | HTTP Status | Meaning | Retry? |
|-----------|-------------|---------|--------|
| AUTH_1001 | 401 | Invalid credentials | No — user must re-enter |
| AUTH_1002 | 401 | Token expired | Yes — refresh token |
| AUTH_1003 | 403 | Permission denied | No — user lacks permission |
| AUTH_1009 | 429 | Rate limited (failed logins) | Yes (after backoff) |
| TNT_2001 | 404 | Tenant not found | No — verify tenant exists |
| TNT_2004 | 403 | Tenant suspended | No — contact support |
| PAY_5001 | 402 | Payment failed | Yes — retry with new order |
| PAY_5004 | 502 | Razorpay API error | Yes (with exponential backoff) |
| RES_4001 | 404 | Resource not found | No — verify resource ID |
| RES_4005 | 402 | Plan limit exceeded | No — upgrade plan |
| SUB_7002 | 409 | Upgrade pending | No — complete/cancel first |
| VAL_6001 | 400 | Validation failed | No — fix input and retry |
| GEN_9001 | 500 | Internal server error | Yes (with exponential backoff) |


---

## Error Handling Patterns

### Pattern 1: Safe Repository Access

```java
// WRONG: Assumes entity exists
User user = userRepository.findById(userId).get();

// CORRECT: Handles not-found
User user = userRepository.findById(userId)
    .orElseThrow(() -> ResourceException.notFound("User", userId));
```

**HTTP response if not found:**
```
404 Not Found
{
  "success": false,
  "message": "User with ID: 123",
  "errorCode": "RES_4001"
}
```


### Pattern 2: Tenant Isolation Error

```java
// WRONG: Query tenant schema without setting context
Subscription sub = subscriptionRepository.findById(subId).orElseThrow();
// Hits public schema or throws constraint error

// CORRECT: Set context first
TenantContext.setTenantId(tenantId);
TenantContext.setCurrentSchema(schemaName);
try {
    Subscription sub = subscriptionRepository.findById(subId)
        .orElseThrow(() -> ResourceException.notFound("Subscription", subId));
} finally {
    TenantContext.clear();
}
```


### Pattern 3: Payment Verification Error

```java
// Service layer
PaymentService.verifyAndCapturePayment(paymentId, signature) {
    Payment payment = paymentRepository.findById(paymentId)
        .orElseThrow(() -> ResourceException.notFound("Payment", paymentId));
    
    if (!RazorpayVerifier.verifySignature(payment, signature)) {
        throw PaymentException.invalidSignature();  // 400 Bad Request
    }
    
    // Proceed with capture...
}
```

**HTTP response if signature invalid:**
```
400 Bad Request
{
  "success": false,
  "message": "HMAC-SHA256 signature verification failed",
  "errorCode": "PAY_5005"
}
```


### Pattern 4: Validation Error

```java
@PostMapping("/subscriptions/upgrade")
public ApiResponse<SubscriptionResponse> upgradeSubscription(
        @Valid @RequestBody UpgradeRequest request) {
    // If @Valid fails, GlobalExceptionHandler catches MethodArgumentNotValidException
    // Returns 400 with field errors
    
    SubscriptionResponse response = subscriptionService.upgrade(request);
    return ApiResponse.success(response);
}
```

**HTTP response if email invalid:**
```
400 Bad Request
{
  "success": false,
  "message": "Validation failed",
  "errorCode": "VAL_6001",
  "fieldErrors": {
    "planId": "must not be null",
    "billingCycle": "invalid billing cycle"
  }
}
```


---

## Flow Diagram

```
API Request
  ↓
@RequestMapping → Controller method
  ├─ Parameter validation (@Valid)
  │   └─ Invalid → MethodArgumentNotValidException
  │       ↓ (caught by GlobalExceptionHandler)
  │       └─ 400 Bad Request + fieldErrors
  ├─ @PreAuthorize("hasAuthority('...')")
  │   └─ Denied → AccessDeniedException
  │       ↓ (caught by GlobalExceptionHandler)
  │       └─ 403 Forbidden
  ├─ Call service method
  │   ├─ Business logic validation fails
  │   │   └─ throw SubscriptionException.invalidTransition()
  │   │       ↓ (extends BaseException)
  │   │       └─ Caught by handleBaseException()
  │   │           └─ Response: 400 errorCode=SUB_7003
  │   ├─ Payment verification fails
  │   │   └─ throw PaymentException.invalidSignature()
  │   │       ↓
  │   │       └─ Response: 400 errorCode=PAY_5005
  │   └─ Razorpay API error
  │       └─ throw PaymentException.razorpayError(code, msg)
  │           ↓
  │           └─ Response: 502 errorCode=PAY_5004
  ├─ Success → return data
  │   ↓
  │   └─ 200 OK + ApiResponse.success(data)
  ├─ Unexpected exception (e.g., NullPointerException)
  │   └─ Caught by handleGenericException()
  │       └─ 500 Internal Server Error + errorCode=GEN_9001
  ↓
MdcLoggingFilter (finally block)
  ├─ Extract errorCode from response
  ├─ Add to MDC: errorCode, errorMessage
  └─ Log: "Request failed: errorCode=PAY_5005, tenantId=456"
```


---

## Code References

| Component | Tag | Location | Purpose |
|-----------|-----|----------|---------|
| BaseException | [SHD-18] | [src/main/java/com/mtbs/shared/exception/BaseException.java](../../src/main/java/com/mtbs/shared/exception/BaseException.java) | Exception base class |
| ErrorCode | [SHD-19] | [src/main/java/com/mtbs/shared/exception/ErrorCode.java](../../src/main/java/com/mtbs/shared/exception/ErrorCode.java) | Error code enum |
| AuthException | [SHD-21] | [src/main/java/com/mtbs/shared/exception/AuthException.java](../../src/main/java/com/mtbs/shared/exception/AuthException.java) | Auth-specific exceptions |
| ResourceException | [SHD-20] | [src/main/java/com/mtbs/shared/exception/ResourceException.java](../../src/main/java/com/mtbs/shared/exception/ResourceException.java) | Resource access exceptions |
| PaymentException | [SHD-23] | [src/main/java/com/mtbs/shared/exception/PaymentException.java](../../src/main/java/com/mtbs/shared/exception/PaymentException.java) | Payment exceptions |
| SubscriptionException | [SHD-25] | [src/main/java/com/mtbs/shared/exception/SubscriptionException.java](../../src/main/java/com/mtbs/shared/exception/SubscriptionException.java) | Subscription exceptions |
| TenantException | [SHD-22] | [src/main/java/com/mtbs/shared/exception/TenantException.java](../../src/main/java/com/mtbs/shared/exception/TenantException.java) | Tenant/onboarding exceptions |
| GlobalExceptionHandler | [APP-10] | [src/main/java/com/mtbs/app/exception/GlobalExceptionHandler.java](../../src/main/java/com/mtbs/app/exception/GlobalExceptionHandler.java) | Centralized exception handling |
| ApiResponse | [SHD-50] | [src/main/java/com/mtbs/shared/dto/common/ApiResponse.java](../../src/main/java/com/mtbs/shared/dto/common/ApiResponse.java) | Standard response format |


---

## Rules & Constraints

1. **All exceptions must extend BaseException** — Never use generic `Exception` or `RuntimeException` directly. Subclass provides context and ErrorCode mapping.

2. **ErrorCode determines HTTP status** — Do not manually set `@ResponseStatus` annotations. All status codes derive from `ErrorCode.httpStatus`. This ensures consistency.

3. **Never expose stack traces in production** — GlobalExceptionHandler never returns stack traces. Detailed errors logged server-side and indexed in ELK/CloudWatch with requestId/tenantId correlation.

4. **Validation errors include field-level details** — `MethodArgumentNotValidException` handler extracts fieldErrors map. Clients see which fields failed and why.

5. **Tenant context always in error logs** — MdcLoggingFilter adds tenantId, userId to every log. Support can diagnose "User X in Tenant Y got error Z" without API debugging.

6. **Unchecked exceptions only** — `BaseException` extends `RuntimeException`. Service methods do NOT declare `throws` clauses. Exceptions percolate to controller and are caught by `GlobalExceptionHandler`.

7. **Third-party errors must be wrapped** — `RazorpayException` wrapped as `PaymentException`. Never expose external API errors directly; wrap with MTBS ErrorCode.

8. **404 vs 403 distinction** — 404 (not found) means resource doesn't exist. 403 (forbidden) means exists but user lacks permission. Clients treat differently.


---

## Failure Scenarios

| Scenario | Exception | Status | Recovery |
|----------|-----------|--------|----------|
| User provides invalid email | `MethodArgumentNotValidException` | 400 | Re-submit with valid email |
| JWT token expired | `AuthException.tokenExpired()` | 401 | Call /refresh endpoint with refresh token |
| User lacks USER_MANAGE permission | `AccessDeniedException` | 403 | Assign permission or use different user role |
| Subscription ID not found | `ResourceException.notFound()` | 404 | Verify subscription exists; list subscriptions |
| Concurrent upgrade requests | `SubscriptionException.upgradePending()` | 409 | Wait for pending upgrade to complete |
| Razorpay API unreachable | `PaymentException.razorpayError()` | 502 | Retry after 5 seconds (exponential backoff) |
| Payment signature invalid | `PaymentException.invalidSignature()` | 400 | User likely tampered with response; fail hard |
| Tenant schema provisioning failed | `TenantException.schemaError()` | 500 | Admin manually cleans up and retries signup |
| Database connection pool exhausted | `HikariPool.PoolInitializationException` | 500 | Caught as generic Exception; retry after delay |
| NullPointerException in service code | Uncaught | 500 | Log with stack trace server-side; user sees generic error |


---

## Edge Cases & Special Handling

### Concurrency: Duplicate Payment Processing

**Scenario:** Client retries payment verification after timeout.

```
Request 1: POST /payments/123/verify → Razorpay captured funds
  ↓
  (network timeout before response sent to client)
  ↓
Client: "Payment failed, retry"
  ↓
Request 2: POST /payments/123/verify → Payment already SUCCEEDED
  ↓
PaymentService detects: if (payment.status == SUCCEEDED)
  throw PaymentException.paymentAlreadyProcessed()  // 409 Conflict
```

**Client action:** Redirect to invoice list (payment already applied).


### Cross-Tenant Data Access Attempt

**Scenario:** User A (Tenant 1) tries to access User B (Tenant 2) via ID.

```
TenantContext.setTenantId(456);      // Tenant 2
TenantContext.setCurrentSchema("s_456");

userRepository.findById(userBId)     // Query in s_456 schema
  // Returns User B ✓ (correct schema)
  └─ User A's JwtAuthenticationFilter has tenantId=123 in token
  └─ Request comes in with Authorization: Bearer <jwt_for_tenant_1>
  └─ But JwtAuthenticationFilter reads tenantId from COOKIE/HEADER
  └─ SECURITY CHECK: @PreAuthorize can verify TenantContext matches request tenant

// If mismatch detected:
throw ResourceException.accessDenied("Attempting to access different tenant's data")
```

**Design:** JwtAuthenticationFilter sets TenantContext. Controller @PreAuthorize("...") validates tenant context matches JWT. Prevents bugs where developer overwrites TenantContext mid-request.


### Timezone in Error Timestamps

**Design:** ApiResponse.timestamp uses `Instant.now()` (UTC). Never `LocalDateTime.now()`.

```java
// Correct
private Instant timestamp = Instant.now();  // 2026-04-25T10:30:00Z

// Wrong
private LocalDateTime timestamp = LocalDateTime.now();  // No timezone info
```


### Empty vs Null Response

**Scenario:** User has no subscriptions.

```
GET /subscriptions
  ↓
subscriptionRepository.findAll() returns []
  ↓
Return 200 OK (not 404):
{
  "success": true,
  "data": [],
  "timestamp": "..."
}
```

**Never 404 for empty collections** — 404 is for "endpoint doesn't exist", not "no records matched".


---

## Known Issues & Limitations

### 1. Spring Boot Default Error Handling Interference

**Issue:** If `server.error.include-stack-trace=always`, stack traces leak to clients (security risk).

**Mitigation:** Explicitly set `server.error.include-stacktrace: never` in application.yaml. Verified in applications-prod.yaml.


### 2. Async Exception Handler Gap

**Issue:** Exceptions in `@Async` methods are not caught by `GlobalExceptionHandler`.

```java
@Async
public void sendWelcomeEmail(User user) {
    // Exception here is swallowed (logged but not handled)
    emailService.send(user.getEmail(), "...");
}
```

**Mitigation:** Use `AsyncUncaughtExceptionHandler` for @Async exceptions. Currently not configured; exceptions logged by Spring's default handler. Acceptable because welcome email non-critical (async, best-effort).


### 3. Validation Error Message Localization

**Issue:** Field error messages are English-only (no i18n).

```
"email": "must be a valid email address"  // Always English
```

**Mitigation:** Frontend can translate error codes to user's language. Backend keeps messages English for logging/debugging.


---

## Future Improvements

1. **API Error Catalog** — Publish static error codes + HTTP statuses for client developers. OpenAPI 3.1 schema with error examples.

2. **Structured Error Details** — Add `errorDetails` object for retryable errors:
   ```json
   {
     "errorCode": "PAY_5004",
     "retryable": true,
     "retryAfterSeconds": 5,
     "maxRetries": 3
   }
   ```

3. **Error Metrics/Alerting** — Export error counts by ErrorCode to Prometheus. Alert on:
   - Error rate spike (e.g., PAY_5004 > 10% of payment requests)
   - New error codes appearing (unknown exceptions)

4. **Tenant-Specific Error Handling** — Allow tenants to configure whether errors should be emailed to admins (e.g., SUBSCRIPTION_INVALID_TRANSITION).

5. **Audit Trail for Errors** — Log all errors >= 400 status to AuditLog table with tenantId: "User X got error Y at 10:30 UTC".


---

## Related Documents

- [request-flow.md](../01-architecture/request-flow.md) — Where error handling fits in filter chain
- [entities.md](../07-data-model/entities.md) — Exception handling in persistence layer
- [cross-tenant-safety.md](../02-multi-tenancy/cross-tenant-safety.md) — TenantContext validation errors
- [payment-processing.md](../05-platform-billing/payment-processing.md) — PaymentException specifics
- [auth-api.md](../06-api/auth-api.md) — AuthException scenarios
