---
version: 1.0
date: 2026-05-16
author: Manoj Pandi
status: Production Ready
tags:
  - reliability
  - retry-logic
  - exponential-backoff
  - idempotency
  - error-handling
  - fault-tolerance
related_documents:
  - ./scheduler-jobs.md
  - ../01-architecture/outbox-pattern.md
  - ../05-platform-billing/payment-processing.md
  - ../06-api/error-handling.md
---

# Retry Mechanisms

## Executive Summary

MTBS implements **multiple retry strategies** across the platform to handle transient failures (network timeouts, rate limits, temporary service outages) while preventing resource exhaustion and infinite loops. Payment failures retry with exponential backoff (1h → 6h → 24h, max 3 attempts). Outbox events retry indefinitely with exponential backoff (1m → 5m → 25m → 2h, capped). Webhook deliveries retry up to 3 times. All retries are **idempotent** — safe to execute multiple times without changing behavior. Without proper retry logic, transient Razorpay timeouts would permanently fail charges, and events would be lost on network hiccups. This document explains the retry strategies, error classification, backoff algorithms, and idempotency guarantees.

---

## Context / Problem

### Why Retries Matter

Network failures are **inevitable** in production:

| Failure | Cause | Manual Fix Cost | Automatic Retry Cost |
|---------|-------|-----------------|----------------------|
| Razorpay API timeout (5xx) | Load spike on payment processor | $1000+ (refund disputes, customer support) | Free (retried in 6 hours, succeeds) |
| Email server offline | Transient network partition | $500+ (customer confusion, support tickets) | Free (retried, email eventually sent) |
| Webhook delivery timeout | Client temporarily unreachable | $200+ (manual re-delivery investigation) | Free (automatic re-delivery) |
| Event lost on process crash | Unhandled exception during event publish | $$$$ (data corruption, billing issues) | Free (outbox event replayed on restart) |

**Key insight:** Retries are cheaper than manual intervention.

### Why Not Just Retry Forever?

**Problem:** Without limits, retries cause:
- Database bloat (millions of retry records)
- Wasted CPU cycles (retry expensive operations repeatedly)
- Delayed response to permanent failures (don't fail fast)
- Retry storms (all services retrying simultaneously, cascading failures)

**Solution:** Classify errors (transient vs. permanent), retry transient only, fail fast on permanent.

### Why Idempotency?

```
WITHOUT idempotency:
  Payment attempt 1: Razorpay timeout (order not created)
  Retry attempt 2: Creates order X, charges ₹500
  Network partition blocks response
  Retry attempt 3: Creates order X again, charges ₹500 AGAIN
  Result: ₹1000 charged instead of ₹500 (WRONG!)

WITH idempotency (idempotencyKey):
  Payment attempt 1: Razorpay order X, charges ₹500
  Retry attempt 2: Same idempotencyKey → Razorpay returns existing order X
  Retry attempt 3: Same idempotencyKey → Razorpay returns existing order X
  Result: ₹500 charged exactly once (CORRECT!)
```

---

## Dependencies

### Inbound (What triggers retries)
- **Job scheduler** (`PaymentRetryJob`, `OutboxEventProcessor`) — Periodic retry checks
- **API endpoints** — Webhook retries triggered by external event

### Outbound (What retries call)
- `PaymentGatewayPort` → `createOrder()` (with idempotencyKey)
- `ApplicationEventPublisher` → `publishEvent()` (outbox events)
- `PaymentRepository`, `OutboxEventRepository` → Query/update retry state
- `OutboxRepository` → Distributed lock (SELECT FOR UPDATE)

### Configuration
- `application.yaml` key: `app.payment.max-retry-count` — Payment max attempts (default 3)
- `application.yaml` key: `app.outbox.backoff-base` — Outbox backoff base (default 5)
- `application.yaml` key: `app.outbox.cleanup-cron` — Cleanup schedule (default `0 0 3 * * ?`)
- Razorpay headers: `X-Razorpay-Request-Id` — Idempotency via request ID (if Razorpay 2.0 API)

---

## Design / Implementation

### Retry Strategy Overview

```
┌────────────────────────────────────────────────────────────────┐
│                    MTBS Retry System                           │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────────────────┐  ┌─────────────────────────────┐   │
│  │  Payment Retry       │  │  Outbox Event Retry        │   │
│  ├──────────────────────┤  ├─────────────────────────────┤   │
│  │ Max: 3 attempts      │  │ Max: 5+ attempts (until ok) │   │
│  │ Backoff:             │  │ Backoff:                    │   │
│  │   0: immediate       │  │   0: 1 minute               │   │
│  │   1: 1 hour          │  │   1: 5 minutes              │   │
│  │   2: 6 hours         │  │   2: 25 minutes             │   │
│  │   3: 24 hours        │  │   3: 2 hours (capped)       │   │
│  │ After max: suspend   │  │ After max: dead-letter      │   │
│  └──────────────────────┘  └─────────────────────────────┘   │
│                                                                │
│  ┌──────────────────────┐                                     │
│  │  Webhook Retry       │                                     │
│  ├──────────────────────┤                                     │
│  │ Max: 3 attempts      │                                     │
│  │ Backoff: exponential │                                     │
│  │   (by external API)  │                                     │
│  └──────────────────────┘                                     │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### Error Classification

Before retrying, classify the error:

```java
// From OutboxEventProcessor.classifyError()

public ErrorType classifyError(Exception e) {
    // VALIDATION errors (invalid data) → Don't retry
    if (e instanceof IllegalArgumentException || 
        e instanceof IllegalStateException) {
        return ErrorType.VALIDATION;  // PERMANENT
    }
    
    // TRANSIENT errors (temporary) → Retry
    if (e instanceof jakarta.mail.MessagingException) {
        return ErrorType.TRANSIENT;  // RETRY
    }
    
    if (e.getCause() instanceof SocketTimeoutException ||
        e.getCause() instanceof ConnectException) {
        return ErrorType.TRANSIENT;  // RETRY
    }
    
    // DESERIALIZATION errors (code issue) → Don't retry
    if (e instanceof RuntimeException && 
        e.getMessage() != null &&
        e.getMessage().contains("Failed to deserialize")) {
        return ErrorType.PERMANENT;  // DON'T RETRY
    }
    
    return ErrorType.UNKNOWN;  // Default: retry
}
```

**Error Types:**
- **TRANSIENT** — Temporary issue; retry will likely succeed
  - Network timeouts, connection refused, 503 Service Unavailable, rate limits
  - Email service down, SMS gateway busy
- **PERMANENT** — Fundamental problem; retry will always fail
  - Invalid data, deserialization error, authorization failure, 404 Not Found
  - Missing required field, corrupted payload
- **VALIDATION** — Invalid request; never retry
  - Negative amount, invalid enum value, constraint violation

---

## Payment Retry Strategy

### Overview

**Max attempts:** 3 (initial + 2 retries)

**Backoff schedule:**
- Attempt 0: Immediate (user clicked pay)
- Attempt 1: 1 hour later (if payment failed)
- Attempt 2: 6 hours later (if retry 1 failed)
- Attempt 3: 24 hours later (if retry 2 failed)
- After 3 attempts: Mark FAILED, suspend subscription

### Implementation

**Flow in PaymentService:**

```java
@Transactional
public void handlePaymentFailure(String razorpayPaymentId, String failureCode, String failureMessage) {
    Payment payment = paymentRepository.findByRazorpayPaymentId(razorpayPaymentId)
        .orElseThrow();
    
    payment.setStatus(PaymentStatus.FAILED);
    payment.setFailureCode(failureCode);
    payment.setFailureMessage(failureMessage);
    
    // Decide whether to retry
    if (payment.getRetryCount() < MAX_RETRY_COUNT) {  // 0, 1, or 2
        Duration delay = switch (payment.getRetryCount()) {
            case 0 -> Duration.ofHours(1);      // 1h after attempt 0
            case 1 -> Duration.ofHours(6);      // 6h after attempt 1
            default -> Duration.ofHours(24);    // 24h after attempt 2
        };
        payment.setNextRetryAt(Instant.now().plus(delay));
        log.info("Scheduling retry #{} in {}", payment.getRetryCount() + 1, delay);
    } else {
        // Exceeded max retries → Suspend subscription
        log.error("Max retries exceeded for payment {}", payment.getId());
        suspendSubscription(payment.getInvoiceId());
    }
    
    paymentRepository.save(payment);
}
```

### Idempotency Pattern for Payments

**Challenge:** Razorpay creates new order on each request. Retry with same parameters = duplicate order + duplicate charge.

**Solution:** Idempotency key.

```java
String idempotencyKey = "pay-" + tenantId + "-" + invoiceId;

// Razorpay 1.0 API (current):
// Order created with receipt = idempotencyKey
// Razorpay stores receipt, doesn't check for duplicates
// Application-side check: find existing payment by idempotencyKey

// Retry handling:
OrderResponse order = paymentGateway.createOrder(
    amountInPaise, 
    currency, 
    idempotencyKey + "-retry-" + (retryCount + 1)  // Unique per retry
);

// This creates a NEW order, but invoice remains the same
// Old failed order ID stored in Payment table with status FAILED
// New order ID used for next attempt
```

**Outcome:** Multiple orders created (one per attempt), but only ONE succeeds and is marked PAID. Others remain FAILED.

### Example Scenario

```
2026-05-15 14:00: Tenant upgrades to PRO
  Invoice: INV-2026-00123, Amount: ₹500
  Payment attempt 0:
    paymentService.processPayment(INV-2026-00123)
    idempotencyKey: "pay-456-00123"
    Razorpay order: ORD-789
    razorpayOrderId stored in Payment table
    Status: PENDING
    
2026-05-15 14:05: User completes checkout, but network timeout
  Signature verification fails
  Status remains PENDING
  
2026-05-15 14:10: Razorpay webhook (payment.failed event)
  handlePaymentFailure() called
  Status: FAILED
  failureCode: "network_error"
  retryCount: 0
  nextRetryAt: 2026-05-15 15:10 (1 hour later)
  
2026-05-15 15:10: PaymentRetryJob runs
  nextRetryAt < now? YES
  retryCount < 3? YES
  retryFailedPayment(paymentId)
    idempotencyKey: "pay-456-00123-retry-1"
    Razorpay order: ORD-790 (NEW)
    Status: PENDING (reset for retry)
    retryCount: 1
    
2026-05-15 15:20: User retries payment manually
  Signs new order ORD-790
  Payment succeeds
  Status: SUCCEEDED
  Invoice status: PAID
  
2026-05-15 16:10: PaymentRetryJob runs again
  nextRetryAt: null (payment succeeded)
  Skip this payment
```

---

## Outbox Event Retry Strategy

### Overview

**Max attempts:** Unlimited (until success or permanent error)

**Backoff schedule (exponential):**
- Attempt 0: Immediate (on event creation)
- Attempt 1: 1 minute after failure
- Attempt 2: 5 minutes after attempt 1 failure
- Attempt 3: 25 minutes after attempt 2 failure
- Attempt 4: 125 minutes (~2h) after attempt 3 failure
- Attempt 5+: Capped at 125 minutes (max backoff)

**Backoff formula:**
```
backoff_seconds = min(5^(retryCount-1) * 60, 125 * 60)  // capped at ~2h
```

**Examples:**
- Attempt 1: 5^0 * 60 = 60 seconds (1 minute)
- Attempt 2: 5^1 * 60 = 300 seconds (5 minutes)
- Attempt 3: 5^2 * 60 = 1500 seconds (25 minutes)
- Attempt 4: 5^3 * 60 = 7500 seconds (2.08 hours) → capped at 7500s (~2h)
- Attempt 5+: Always 7500 seconds (max)

### Implementation

```java
// From OutboxEventProcessor.java

private long calculateBackoff(int retryCount) {
    if (retryCount <= 1) {
        return 60;  // 1 minute for first attempt
    }
    // Exponential: 5^(n-1) * 60 seconds
    long backoff = (long) Math.pow(5, retryCount - 1) * 60;
    
    // Cap at ~10 hours (36000 seconds)
    return Math.min(backoff, 36000);
}

// On failure:
if (outboxEvent.getStatus() != OutboxStatus.FAILED_PERMANENTLY) {
    long backoffSeconds = calculateBackoff(outboxEvent.getRetryCount());
    outboxEvent.setNextRetryAt(Instant.now().plus(Duration.ofSeconds(backoffSeconds)));
    outboxEvent.setRetryCount(outboxEvent.getRetryCount() + 1);
    outboxRepository.save(outboxEvent);
}
```

### Error Classification for Outbox

| Error | Classification | Retry? | Example |
|-------|-----------------|--------|---------|
| EmailService not responding | TRANSIENT | YES | `jakarta.mail.MessagingException` |
| Network timeout | TRANSIENT | YES | `SocketTimeoutException` |
| Database unavailable | TRANSIENT | YES | `DataAccessException` |
| Invalid event data | VALIDATION | NO | `IllegalArgumentException` |
| Deserialization failed | PERMANENT | NO | `Failed to deserialize` |
| Authorization failed | PERMANENT | NO | `403 Forbidden` |

### Example Scenario

```
2026-05-15 14:00: Invoice created event
  OutboxEvent saved to outbox_events table
  eventType: "InvoiceCreatedEvent"
  status: PENDING
  retryCount: 0
  
OutboxEventProcessor polls immediately:
  Publish to ApplicationEventPublisher
  NotificationEventListener throws exception:
    "Failed to connect to email server"
  Error classified as TRANSIENT
  status: FAILED (will be retried)
  retryCount: 1
  nextRetryAt: 2026-05-15 14:01 (1 minute later)
  
2026-05-15 14:01: OutboxEventProcessor polls
  Found event with nextRetryAt < now
  Publish again
  Email service STILL down
  Error classified as TRANSIENT
  status: FAILED
  retryCount: 2
  nextRetryAt: 2026-05-15 14:06 (5 minutes later)
  
2026-05-15 14:06: OutboxEventProcessor polls
  Publish again
  Email service restored
  Listeners succeed
  status: PROCESSED
  processedAt: now
  
2026-05-15 03:00 (next day): Cleanup job runs
  DELETE outbox_events WHERE status='PROCESSED' AND processedAt < (now - 30 days)
  Removes stale processed events
```

### Dead-Letter Handling

Events that fail permanently (invalid data, deserialization error) are moved to dead-letter:

```java
if (errorType == ErrorType.PERMANENT || errorType == ErrorType.VALIDATION) {
    outboxEvent.setStatus(OutboxStatus.FAILED_PERMANENTLY);
    outboxEvent.setErrorType(errorType);
    log.error("DEAD_LETTER: Event {} failed permanently: {}",
        outboxEvent.getId(), outboxEvent.getErrorMessage());
}
```

**Monitoring Alert:** Log line triggers: "DEAD_LETTER" detected → On-call engineer investigates.

---

## Webhook Retry Strategy

### Overview

Webhooks from external services (Razorpay) may fail to deliver to MTBS. MTBS does NOT retry webhooks; instead, **Razorpay retries delivery** up to 3 times.

However, MTBS webhook handlers are **idempotent** to handle duplicate deliveries:

```java
@Transactional
public void handlePaymentSuccess(String razorpayPaymentId) {
    paymentRepository.findByRazorpayPaymentId(razorpayPaymentId)
        .ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                return;  // Already processed — idempotent!
            }
            
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setPaidAt(Instant.now());
            paymentRepository.save(payment);
            
            log.info("Webhook processed (idempotent): {}", razorpayPaymentId);
        });
}
```

**Why idempotent?**
If Razorpay retries webhook delivery and we don't check, we'd mark payment SUCCEEDED multiple times (non-idempotent). By checking status first, duplicate webhook deliveries are safely ignored.

---

## Flow Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                  RETRY DECISION TREE                             │
└────────┬─────────────────────────────────────────────────────────┘
         │
         ▼
    Operation fails
    (API call, event publish, etc.)
         │
         ├─ Classify error ──┐
         │                   │
         │   ┌───────────────┼───────────────┐
         │   │               │               │
         ▼   ▼               ▼               ▼
      TRANSIENT         PERMANENT       VALIDATION
      (temporary)       (will fail)     (invalid data)
         │                   │               │
         ├─ Classify? ──┐    ├─ Log error    └─ Log error + ALERT
         │              │    │               (likely code bug)
         │              └────┴─ No retry
         │
         ├─ Check max attempts
         │
         ├─ Exceeded? ───┐
         │               │
         │           YES ├─ Mark FAILED
         │               │  Suspend/escalate
         │               │
         │           NO
         │               │
         ├─────────────────┴─ Calculate backoff
         │
         ├─ Schedule retry
         │  (nextRetryAt = now + backoff)
         │
         └─ Save state to DB
            
Next poll (scheduler):
  IF nextRetryAt < now:
    Execute retry (GOTO Operation fails)
```

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `PaymentService` | [BIL-4] | `handlePaymentFailure()` | Classify error, schedule payment retry |
| `PaymentService` | [BIL-4] | `retryFailedPayment()` | Execute payment retry with new order |
| `OutboxEventProcessor` | [BIL-35] | `handleFailure()` | Classify outbox error, schedule retry |
| `OutboxEventProcessor` | [BIL-35] | `calculateBackoff()` | Exponential backoff formula |
| `OutboxEventProcessor` | [BIL-35] | `classifyError()` | Error classification (transient/permanent/validation) |
| `Payment` entity | [BIL-8] | `retryCount`, `nextRetryAt` | Retry state fields |
| `OutboxEvent` entity | [BIL-36] | `retryCount`, `nextRetryAt`, `errorType` | Retry state fields |
| `PaymentRetryJob` | [BIL-64] | `execute()` | Trigger payment retries periodically |

---

## Rules / Constraints

1. **Idempotency Keys Must Be Tenant-Scoped** — `"pay-" + tenantId + "-" + invoiceId` prevents cross-tenant collisions. Different tenants can have same invoiceId but different tenantId ensures unique key.

2. **Exponential Backoff Must Be Capped** — Without cap, 5^50 * 60 = infinity. Backoff capped at max (typically 2-10 hours) prevents retry storms.

3. **Permanent Errors Must Not Retry** — Retrying deserialization error or validation error wastes CPU. Classify first, retry only transient.

4. **Distributed Lock Prevents Duplicate Retries** — SELECT FOR UPDATE SKIP LOCKED ensures only one instance processes an event. No coordination needed between instances.

5. **Retry State Stored in Database** — `retryCount` and `nextRetryAt` persisted, survives process crash. On restart, job resumes retries where it left off.

6. **Max Retry Count Prevents Infinite Loops** — Payment max 3, outbox max 5+. Without limit, failed operations retry forever, consuming resources forever.

7. **Logging Must Include Retry Context** — Every retry log includes `retryCount`, `nextRetryAt`, `errorType`. Enables debugging of retry patterns.

---

## Failure Scenarios

| Scenario | Error Detected | Recovery |
|----------|---|---|
| Razorpay API timeout (500) | `SocketTimeoutException` | Classified TRANSIENT → Retry after 1h |
| Payment data corrupted (invalid amount) | `IllegalArgumentException` | Classified VALIDATION → Don't retry, log ALERT |
| Email service offline | `MessagingException` | Classified TRANSIENT → Retry after 1m, then 5m, then 25m |
| Database connection lost during outbox processing | `DataAccessException` | Classified TRANSIENT → Retry after 1m |
| Webhook delivered twice (duplicate) | (None — handler idempotent) | Check status first → Skip if already processed |
| Max retries exceeded (payment) | (RetryCount >= 3) | Suspend subscription, notify tenant |
| Event in dead-letter (permanent error) | (Exception + ErrorType.PERMANENT) | Log "DEAD_LETTER", operator investigates manually |

---

## Edge Cases

- **Concurrency: Two instances retry same payment simultaneously** — Database SELECT FOR UPDATE lock prevents this. First instance acquires lock, second waits. First completes, second skips (status changed to SUCCEEDED).

- **Concurrency: Job crashes mid-retry** — `nextRetryAt` already set. On restart, job resumes retry at scheduled time. No double-execution risk.

- **Clock skew: Instance A clock 5 minutes behind Instance B** — `nextRetryAt` is UTC Instant. Clock skew may cause early retry, but harmless (idempotent check prevents duplicate processing).

- **Backoff: Exponential grows to infinity** — Capped at 7500s (2.08h). 5^1000 is huge, but code caps it. No runaway growth.

- **Retry: Event payload corrupted** — Deserialization fails → ErrorType.PERMANENT → No retry. Event moved to dead-letter for manual inspection.

- **Retry: Tenant deleted mid-retry** — Job fetches only ACTIVE tenants. If tenant becomes SUSPENDED/DELETED before retry, job skips it. Retry state remains but never executed.

- **Empty state: No failed payments** — Job queries `FAILED` payments with `nextRetryAt < now`. Empty result → job exits. No attempt made on nothing.

- **Performance: Millions of failed events** — SELECT FOR UPDATE SKIP LOCKED scales well. Database acquires locks only for events being processed, skips others. Partitioning recommended (shard by tenantId).

---

## Known Issues / Limitations

1. **No Jitter in Backoff** — All instances retry at exact same time (nextRetryAt). Causes thundering herd (millions retry simultaneously). Solution: Add random jitter (±10%) to backoff.

2. **No Circuit Breaker** — If external service (email, Razorpay) stays down, retries continue forever (capped, but wasteful). Circuit breaker would fast-fail when service unhealthy.

3. **No Retry Metrics** — No logging of retry counts, success rates, or average retry duration. Observability limited. Add custom Micrometer metrics.

4. **Outbox Dead-Letter Not Exposed** — Failed permanent events hidden in database. No admin UI to view/replay dead-letter queue. Add `/admin/dead-letter` endpoint.

5. **Payment Retry Hardcoded** — Retry durations (1h, 6h, 24h) hardcoded in code. Should be configurable via application.yaml.

6. **No Selective Retry** — All errors retried the same way. Some errors (rate limit 429) should retry sooner; others (auth 401) should fail immediately.

---

## Future Improvements

1. **Add Jitter to Backoff** — Randomize nextRetryAt by ±10% to prevent thundering herd: `nextRetryAt = now + backoff + random(-10%, +10%)`.

2. **Circuit Breaker Pattern** — Track failure rate for each external service. If >50% fail for 5 minutes, stop retrying, fail fast, alert on-call.

3. **Retry Metrics (Micrometer)** — Publish `retry.count`, `retry.success_rate`, `retry.avg_duration` for dashboards and alerts.

4. **Dead-Letter Queue Admin UI** — Endpoint `/admin/dead-letter?status=PERMANENT` lists failed events. Allow manual replay with `POST /admin/dead-letter/{id}/replay`.

5. **Configurable Retry Policies** — Move hardcoded retry durations to application.yaml:
   ```yaml
   app:
     retry:
       payment:
         max-attempts: 3
         backoff:
           - 1h
           - 6h
           - 24h
       outbox:
         max-attempts: -1  # unlimited
         backoff: exponential  # 5^n * 60
         cap: 2h
   ```

6. **Error-Specific Retry Strategies** — Different strategies for different errors:
   - Rate limit (429): Retry after 60s (short backoff)
   - Auth failure (401): Fail immediately (no retry)
   - Timeout (504): Retry exponentially (medium backoff)

7. **Distributed Tracing** — Correlation ID propagates across retries. Trace single payment through all 3 retry attempts.

8. **Retry Cost Analytics** — Track: "How much CPU/bandwidth wasted on retries? What % of retries succeed?" Guide optimization efforts.

---

## Related Documents

- [scheduler-jobs.md](./scheduler-jobs.md) — Job scheduling and execution (triggers retries)
- [outbox-pattern.md](../01-architecture/outbox-pattern.md) — Outbox design (retry enabled by design)
- [payment-processing.md](../05-platform-billing/payment-processing.md) — Payment flow including failures
- [error-handling.md](../06-api/error-handling.md) — Exception hierarchy and classification
- [jwt-token-lifecycle.md](../03-security/jwt-token-lifecycle.md) — Token refresh as retry pattern
