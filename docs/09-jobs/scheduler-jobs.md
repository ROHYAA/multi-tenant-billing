---
version: 1.0
date: 2026-05-16
author: Manoj Pandi
status: Production Ready
tags:
  - scheduler
  - jobs
  - quartz
  - billing
  - event-processing
  - background-tasks
related_documents:
  - ./retry-mechanisms.md
  - ../01-architecture/outbox-pattern.md
  - ../05-platform-billing/subscription-lifecycle.md
  - ../05-platform-billing/invoice.md
  - ../05-platform-billing/payment-processing.md
---

# Scheduler Jobs

## Executive Summary

MTBS uses **Apache Quartz** for reliable, fault-tolerant background job scheduling across all tenants. Eight core jobs handle subscription lifecycle automation (trial expiry, billing cycle generation, payment retries), event processing (transactional outbox), and tenant state management. Each job executes **tenant-aware**, iterating through active tenants and setting `TenantContext` to ensure schema routing works correctly. Without these jobs, subscriptions would never be charged, trials would never expire, and events could be lost on crashes. This document maps all job types, execution schedules, dependencies, failure recovery, and tenant isolation guarantees.

---

## Context / Problem

### Why Background Jobs?

SaaS subscriptions require time-driven actions that cannot wait for user requests:

| Requirement | Manual Alternative (Bad) | Job Solution |
|------------|--------------------------|--------------|
| "Charge tenant every month" | Wait for user to click Pay | `BillingCycleJob` runs nightly |
| "Trial expired; can't use system" | Admin manually suspends tenant | `TrialExpiryJob` auto-marks PAST_DUE |
| "Payment failed; retry later" | Admin manually retries payment | `PaymentRetryJob` retries every 6h |
| "Event lost on crash; notify user" | Manually query crash logs | `OutboxEventProcessor` replays events |

### Why Quartz Over Simple @Scheduled?

Quartz provides:
- ✅ Distributed locking (multiple instances don't duplicate work)
- ✅ Persistent job history and trigger state (survives restarts)
- ✅ Misfire handling (if a job missed its schedule, catch it up)
- ✅ Flexible cron expressions
- ✅ Configurable thread pools

`@Scheduled` does not offer these guarantees.

### Why Tenant-Aware Execution?

MTBS uses per-tenant schemas. Jobs must:
1. Iterate all active tenants
2. Set `TenantContext` for each tenant
3. Route queries to tenant schema
4. Clear `TenantContext` in finally block (prevent data leakage to next tenant)

Missing a single `finally` block = silent cross-tenant bug.

---

## Dependencies

### Inbound (What triggers jobs)
- **Quartz Scheduler**: Cron triggers fire jobs at scheduled times
- **ApplicationServer startup**: Jobs are registered in `QuartzConfig`
- **Manual trigger**: Operators can trigger jobs via admin endpoints (if implemented)

### Outbound (What jobs call)
- `TenantService.getTenantsByStatusList()` → Fetch all active tenants
- `SubscriptionService` → Query, update subscriptions
- `InvoiceService` → Generate, finalize invoices
- `PaymentService` → Process, retry payments
- `OutboxEventRepository` → Query pending outbox events
- `ApplicationEventPublisher` → Publish domain events from outbox
- `TransactionTemplate` → Execute within transaction boundary

### Configuration
- `application.yaml` key: `spring.quartz` — Quartz engine settings
- `application.yaml` key: `app.outbox.batch-size` — Outbox batch size (default 100)
- `application.yaml` key: `app.outbox.poll-interval-ms` — Outbox poll interval (default 5000ms)
- Environment variable: `QUARTZ_THREAD_POOL_SIZE` — Number of worker threads

---

## Design / Implementation

### Job Registry (QuartzConfig.java)

Eight jobs are registered in Spring `@Configuration`:

```java
@Configuration
public class QuartzConfig {
    @Bean JobDetail billingCycleJobDetail() → BillingCycleJob
    @Bean Trigger billingCycleTrigger() → Cron: 0 0 0 * * ? (daily midnight)
    
    @Bean JobDetail subscriptionExpiryJobDetail() → SubscriptionExpiryJob
    @Bean Trigger subscriptionExpiryTrigger() → Cron: 0 0 * * * ? (hourly)
    
    // ... (6 more jobs)
}
```

All triggers use `.withMisfireHandlingInstructionFireAndProceed()` — if a scheduled time is missed (e.g., server restart), fire immediately on restart.

### Tenant Iteration Pattern

**Standard pattern every job follows:**

```java
@Override
public void execute(JobExecutionContext context) {
    log.info("Starting {}", getClass().getSimpleName());
    List<Tenant> tenants = tenantService.getTenantsByStatusList(Status.ACTIVE);
    
    for (Tenant tenant : tenants) {
        try {
            // CRITICAL: Set context for schema routing
            TenantContext.setTenantId(tenant.getId());
            TenantContext.setCurrentSchema(tenant.getSchemaName());
            
            // Process tenant (Hibernate queries now route to tenant schema)
            processForTenant(tenant);
            
        } catch (Exception e) {
            log.error("Error processing tenant {}", tenant.getSchemaName(), e);
        } finally {
            // CRITICAL: Always clear to prevent data leak to next tenant
            TenantContext.clear();
        }
    }
    log.info("{} completed", getClass().getSimpleName());
}
```

**Failure mode if finally block is missing:**
```
Iteration 1: TenantContext.setTenantId(456)  ← Thread pool worker #1
            processForTenant()
            EXCEPTION THROWN, no finally
            
Iteration 2: TenantContext.setTenantId(789)  ← NEW request, same worker #1
            Query reads from context = 456 (WRONG!)
            Tenant 789 data leaked from Tenant 456 schema
```

---

## Job Descriptions

### 1. BillingCycleJob

**Schedule:** Daily at midnight UTC (`0 0 0 * * ?`)

**Purpose:** Generate invoices for all ACTIVE subscriptions whose billing period has ended. Advance period dates to next cycle.

**Flow:**
```
1. Get all ACTIVE subscriptions where currentPeriodEnd < now
2. For each:
   a. Generate invoice (subscription charges + taxes)
   b. Finalize invoice (DRAFT → OPEN)
   c. Process payment (auto-charge via Razorpay order)
   d. Update subscription period dates:
      newStart = currentPeriodEnd
      newEnd = newStart + (MONTHLY=30 days | ANNUAL=365 days)
   e. Save subscription
3. Log summary
```

**Error Recovery:**
- Single subscription failure does not stop job (try-catch inner loop)
- Tenant failures do not stop job (try-catch outer loop)
- Failed invoices remain in DRAFT state; job retries next cycle

**Example Scenario:**
```
Tenant A subscription:
  currentPeriodStart: 2026-05-01
  currentPeriodEnd: 2026-06-01  ← NOW = 2026-06-01
  billingCycle: MONTHLY
  
Job runs 2026-06-01 midnight:
  Generate invoice for May 1 - June 1 → INV-2026-00100
  Finalize INV-2026-00100 (DRAFT → OPEN)
  Process payment (Razorpay order)
  Update subscription:
    currentPeriodStart: 2026-06-01
    currentPeriodEnd: 2026-07-01 (30 days later)
    
NEXT CYCLE: 2026-07-01 midnight → Generate June 1 - July 1 invoice
```

---

### 2. SubscriptionExpiryJob

**Schedule:** Hourly (`0 0 * * * ?`)

**Purpose:** Convert PAST_DUE subscriptions to EXPIRED if they exceed the 7-day grace period. Suspend tenant account.

**Grace Period Logic:**
- Invoice not paid → Subscription becomes PAST_DUE
- 7 days pass with no payment → Subscription becomes EXPIRED
- Tenant status changes to SUSPENDED

**Flow:**
```
1. Get all PAST_DUE subscriptions where periodEnd < (now - 7 days)
2. For each:
   a. Call subscriptionService.expireSubscription()
      (Sets status to EXPIRED, may cancel features)
   b. Call tenantService.updateTenantStatus(tenant, SUSPENDED)
      (Tenant cannot create new invoices or use platform)
   c. Log suspension
3. Summary logged
```

**Why Hourly?**
- Grace period is 7 days; hourly check catches expiry quickly
- Reduces manual intervention (no operator needs to suspend tenants)

**Example Scenario:**
```
Tenant B subscription:
  Status: ACTIVE
  currentPeriodEnd: 2026-05-20
  
2026-05-20 midnight (BillingCycleJob):
  Invoice generated → INV-2026-00200
  Razorpay order created
  Status: ACTIVE (payment pending)
  
2026-05-21 (no payment received):
  Manual check: subscription status → PAST_DUE
  (triggered by PaymentService on failed charge)
  
2026-05-28 (7 days later):
  SubscriptionExpiryJob hourly run:
  periodEnd (2026-05-20) < now-7days (2026-05-21)? YES
  expireSubscription()
  updateTenantStatus(SUSPENDED)
  Tenant B account locked, users cannot login
```

---

### 3. TrialExpiryJob

**Schedule:** Daily at midnight UTC (`0 0 0 * * ?`)

**Purpose:** Expire trials that have ended. Transition from TRIALING → PAST_DUE, generate invoice, and begin grace period.

**Flow:**
```
1. Get all TRIALING subscriptions where trialEnd < now
2. For each:
   a. Generate invoice for the subscription plan
      (Invoice amount = plan monthly price)
   b. Mark subscription as PAST_DUE (not EXPIRED yet)
      (Gives 7-day payment grace period)
   c. Create Razorpay payment link
   d. Publish TRIAL_EXPIRED event for notification
   e. Log summary
3. Report: "Expired 45 trials, generated 45 invoices"
```

**No Subscription Auto-Downgrade:**
- Trial expired does NOT downgrade to Free plan
- Trial expired DOES require immediate payment
- If payment not received in 7 days, SubscriptionExpiryJob suspends tenant

**Example Scenario:**
```
Tenant C subscription:
  Status: TRIALING
  trialStart: 2026-05-01
  trialEnd: 2026-05-16 (15-day trial)
  planId: PRO
  
2026-05-16 midnight (TrialExpiryJob):
  trialEnd (2026-05-16) < now (2026-05-16)? YES
  Generate invoice for PRO plan → INV-2026-00300 (₹500)
  Set status: PAST_DUE
  Create Razorpay payment link
  Publish TRIAL_EXPIRED event → sends email with link
  
2026-05-17:
  Tenant C user logs in, sees "Trial expired. Please pay ₹500."
  
2026-05-23 (7 days later, no payment):
  SubscriptionExpiryJob marks EXPIRED, suspends tenant
```

---

### 4. TrialEndingSoonJob

**Schedule:** Daily at 08:00 UTC (`0 0 8 * * ?`)

**Purpose:** Notify users 3 days before trial expires. Generate invoice + payment link so users can pre-pay before trial ends.

**Flow:**
```
1. Get all TRIALING subscriptions where:
   trialEnd is between now AND (now + 3 days)
2. For each:
   a. Fire TRIAL_ENDING_SOON event
      (Triggers email notification: "Trial ends in X days")
   b. Generate invoice for the subscription plan
   c. Create Razorpay payment link
   d. Append link to notification
   e. Send email: "Your trial ends in 2 days. [PAY NOW] button"
3. Summary logged
```

**Why 08:00 UTC?**
- Runs before "business hours" in most timezones
- Users see notification when they start their day
- Increases payment likelihood before trial auto-expires

**Example Scenario:**
```
Tenant D subscription:
  Status: TRIALING
  trialEnd: 2026-05-20 (3 days from now on 2026-05-17)
  planId: ENTERPRISE
  
2026-05-17 08:00 UTC (TrialEndingSoonJob):
  now + 3 days window includes 2026-05-20? YES
  Publish TRIAL_ENDING_SOON event
  Generate invoice → INV-2026-00400 (₹2000)
  Create payment link: https://razorpay.com/i/...
  
Email sent to tenant:
  Subject: "Your trial ends in 3 days!"
  Body: "Upgrade now: [PAY ₹2000]"
  
2026-05-20:
  User clicks "PAY ₹2000", completes payment
  TrialExpiryJob skips this subscription (already paid)
  Status: ACTIVE (billing cycle continues)
```

---

### 5. PaymentRetryJob

**Schedule:** Every 6 hours (`0 0 */6 * * ?`)

**Purpose:** Automatically retry payments that failed with transient errors (network timeout, Razorpay temporary outage).

**Flow:**
```
1. Get all FAILED payments where:
   nextRetryAt < now AND retryCount < 3
2. For each:
   a. Increment retryCount
   b. Call paymentService.retryFailedPayment()
      (Attempts to charge again via Razorpay)
   c. If successful:
      - Mark payment as CAPTURED
      - Invoice transitions to PAID
   d. If failed:
      - Increment retryCount
      - Schedule next retry: now + backoff_duration
      - Log error
3. Summary logged
```

**Retry Strategy:**
- Up to 3 retry attempts
- Backoff: exponential (1s, 2s, 4s, 8s, capped at 5 min)
- After 3 failures: manual intervention required

**Example Scenario:**
```
2026-05-15 15:00:
  PaymentService charges Razorpay
  Razorpay returns: 504 Gateway Timeout (transient)
  Mark payment FAILED
  Schedule nextRetryAt = 15:00 + 1min

2026-05-15 15:01:
  PaymentRetryJob (every 6h, but triggered at 15:01 for demo):
  nextRetryAt (15:01) < now (15:01)? YES
  retryCount (0) < 3? YES
  Retry payment → Razorpay returns 200 OK
  Mark payment CAPTURED
  Invoice transitions OPEN → PAID
  
LOG: "Retried payment #xyz (attempt 1/3) — SUCCESS"
```

---

### 6. SubscriptionCancelJob

**Schedule:** Hourly (`0 0 * * * ?`)

**Purpose:** Execute scheduled subscription cancellations queued at period end via `subscription.setCancelAtPeriodEnd(true)`.

**Flow:**
```
1. Get all subscriptions where:
   cancelAtPeriodEnd = true AND currentPeriodEnd < now
2. For each:
   a. Call subscriptionService.executeScheduledCancellation()
      (Sets status to CANCELLED)
   b. If invoice pending, void it
   c. Publish SUBSCRIPTION_CANCELLED event
   d. Log cancellation
3. Summary logged
```

**Use Case:**
```
Tenant E user: "Cancel my subscription at end of month"
  Action: POST /api/v1/subscriptions/cancel?atPeriodEnd=true
  Backend sets: subscription.cancelAtPeriodEnd = true
  Status remains ACTIVE until period end
  User can still use platform
  
2026-06-01 midnight:
  BillingCycleJob generates June invoice
  
2026-06-01 01:00:
  SubscriptionCancelJob:
  cancelAtPeriodEnd = true AND periodEnd < now? YES
  executeScheduledCancellation()
  Status: CANCELLED
  Tenant E cannot renew, platform access revoked
  
LOG: "Cancelled subscription #xyz at period end for tenant E"
```

---

### 7. OutboxEventProcessor

**Schedule:** Continuous polling via `@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")`

**Purpose:** Reliable at-least-once event delivery. Process pending outbox events, publish to `ApplicationEventPublisher`, mark processed.

**Flow:**
```
1. For each active tenant:
   a. Set TenantContext
   b. Call processOutboxForTenant():
      
      2a. Recover stale events (locked > 30s) → reset status to PENDING
      
      2b. Query PENDING events (with SELECT FOR UPDATE distributed lock)
          Lock prevents multiple instances from processing same event
          
      2c. For each event (up to batchSize):
          - Deserialize JSON to event object
          - Increment attempt counter
          - Publish to ApplicationEventPublisher (synchronous)
          - On success: set status to PROCESSED, record timestamp
          - On failure: set status to FAILED, schedule exponential retry
          
      2d. Unlock batch (remove SELECT FOR UPDATE lock)
   
   c. Clear TenantContext
   
2. Sleep pollIntervalMs (default 5 seconds)
3. Repeat
```

**Distributed Lock Mechanism:**
```sql
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
  AND locked_until < now
FOR UPDATE SKIP LOCKED
LIMIT 100
```

- `FOR UPDATE` — Locks rows exclusively
- `SKIP LOCKED` — Ignore already-locked rows (other instances)
- `locked_until < now` — Expired locks are auto-released
- Prevents two instances from processing same event

**Error Handling:**
```
Event: InvoicePaidEvent
  Attempt 1: Publish → NotificationEventListener throws exception
  Catch exception: status = FAILED, retryCount = 1
  nextRetryAt = now + 1 second
  
Next poll (5s later):
  Attempt 2: nextRetryAt < now? YES
  Publish → Success
  Status = PROCESSED
```

**Example Scenario:**
```
Application creates invoice:
  INSERT INTO outbox_events (id, eventType, payload, status)
  VALUES ('evt-123', 'InvoiceCreatedEvent', '{...}', 'PENDING')
  COMMIT

OutboxEventProcessor next poll (5s later):
  SELECT ... FOR UPDATE
  Found event 'evt-123'
  Deserialize to InvoiceCreatedEvent
  ApplicationEventPublisher.publishEvent(event)
  
NotificationEventListener receives event:
  Send email notification to tenant
  
BillingEventListener receives event:
  Log event in audit trail
  
Update outbox_events:
  UPDATE outbox_events SET status='PROCESSED', processed_at=now
  WHERE id='evt-123'
  
Next poll: Event skipped (status=PROCESSED)
```

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    Quartz Scheduler (Main Thread)               │
└─────────────────┬───────────────────────────────────────────────┘
                  │
                  ├─ 00:00 UTC ─→ BillingCycleJob ──────────┐
                  ├─ 00:00 UTC ─→ TrialExpiryJob ──────────┐
                  ├─ 00:00 UTC ─→ SubscriptionCancelJob ───┤
                  ├─ 08:00 UTC ─→ TrialEndingSoonJob ──────┤
                  ├─ Hourly ────→ SubscriptionExpiryJob ───┤
                  ├─ Hourly ────→ SubscriptionCancelJob ───┤
                  ├─ Every 6h ──→ PaymentRetryJob ─────────┤
                  │                                         │
                  └─ Every 5s ──→ OutboxEventProcessor ────┤
                                                            │
        ┌───────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────┐
│  For each ACTIVE tenant:             │
│  1. TenantContext.setTenantId(id)   │
│  2. TenantContext.setSchema(schema) │
│  3. Execute job logic                │
│  4. TenantContext.clear() [finally] │
└──────────────────────────────────────┘
        │
        ├─→ Query tenant schema (Hibernate routes automatically)
        ├─→ Update subscription/invoice/payment
        ├─→ Publish domain events via ApplicationEventPublisher
        └─→ Log to MDC (tenantId, userId, requestId included)
```

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `BillingCycleJob` | [BIL-60] | `execute(JobExecutionContext)` | Generate invoices for completed periods |
| `SubscriptionExpiryJob` | [BIL-61] | `execute(JobExecutionContext)` | Mark PAST_DUE subscriptions as EXPIRED |
| `TrialExpiryJob` | [BIL-62] | `execute(JobExecutionContext)` | Expire trials, generate invoices |
| `TrialEndingSoonJob` | [BIL-63] | `execute(JobExecutionContext)` | Send trial ending notifications |
| `PaymentRetryJob` | [BIL-64] | `execute(JobExecutionContext)` | Retry failed payments with backoff |
| `SubscriptionCancelJob` | [BIL-65] | `execute(JobExecutionContext)` | Execute scheduled cancellations |
| `OutboxEventProcessor` | [BIL-35] | `processOutbox()` | Poll and process outbox events |
| `QuartzConfig` | [APP-6] | Multiple beans | Register jobs and triggers |
| `TenantContext` | [SHARED-1] | `setTenantId()`, `setCurrentSchema()`, `clear()` | ThreadLocal tenant context |
| `SubscriptionService` | [BIL-14] | `findAllByStatusAndPeriodEndBefore()` | Query subscriptions |
| `InvoiceService` | [BIL-2] | `generateInvoice()`, `finalizeInvoice()` | Invoice operations |
| `PaymentService` | [BIL-4] | `processPayment()`, `retryFailedPayment()` | Payment operations |

---

## Rules / Constraints

1. **TenantContext Lifecycle** — Every job must set TenantContext before processing tenant and clear in finally block, regardless of success/failure. Missing clear = cross-tenant data leak.

2. **Schema Routing Automatic** — Once TenantContext is set, Hibernate's `CurrentTenantIdentifierResolverImpl` automatically routes queries to the correct schema. Do not manually pass tenantId to queries.

3. **Idempotency & Retries** — Jobs iterate tenants independently; if Tenant A fails, continue with Tenant B. Each subscription/payment processed in try-catch to prevent cascade failures.

4. **No Long-Running Transactions** — Jobs should not hold database locks across tenant iterations. Process one tenant, commit transaction, move to next tenant.

5. **Logging Context Includes Tenant** — All log statements automatically include tenantId via MDC. Do not manually include tenantId in log message.

6. **Distributed Lock for Outbox** — OutboxEventProcessor uses SELECT FOR UPDATE SKIP LOCKED to prevent multiple instances from processing same event. No application-level semaphore needed.

7. **Misfire Handling** — Triggers configured with `.withMisfireHandlingInstructionFireAndProceed()`. If job missed its schedule, it fires immediately on next scheduler run. No backlog accumulation.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|----------------|-------------|----------|
| Tenant marked DELETED before job starts | `TenantException` | (Job logs error) | Job skips tenant, continues with next |
| Database connection timeout during job | `DataAccessException` | (Job logs error) | Job restarts on next schedule cycle |
| Razorpay API down during invoice charge | `PaymentException` | (Job logs error) | `PaymentRetryJob` retries in 6 hours (exponential backoff) |
| OutboxEventListener throws exception | `Exception` (any) | (Job logs error) | Event marked FAILED, retried exponentially (capped 5min) |
| TenantContext not cleared (bug) | (Silent) | (Next request inherits old context) | Next request queries wrong schema, returns wrong data (CRITICAL BUG) |
| Job interrupted mid-execution (kill -9) | (None — process killed) | (N/A) | Quartz detects job hanging, reschedules next trigger |
| Outbox event already processed by another instance | (None — skip) | (N/A) | SELECT FOR UPDATE ensures only one instance processes; others skip |

---

## Edge Cases

- **Concurrency: Multiple instances running jobs simultaneously** — Quartz uses database-backed scheduler. Only one instance acquires job lock at trigger time. Other instances wait. No duplicate execution.

- **Concurrency: Multiple instances processing same outbox event** — SELECT FOR UPDATE SKIP LOCKED prevents this. If Instance A locks event, Instance B skips and processes next event.

- **Timezone: Jobs scheduled in UTC; tenant in different zone** — All times in database stored as UTC Instant. Business logic (e.g., "charge on day 1 of month") must convert to tenant's local timezone if needed. Currently all billing is UTC.

- **Timezone: Cron expression ambiguity** — All cron expressions are UTC. DST changes do not affect Quartz; Instant handles leap seconds correctly.

- **Tenant Isolation: Job scheduled for SUSPENDED tenant** — Job queries only ACTIVE tenants. SUSPENDED tenants skipped entirely. No cross-contamination possible.

- **Empty State: No active tenants** — `TenantService.getTenantsByStatusList(Status.ACTIVE)` returns empty list. Job iterates nothing, logs "0 tenants processed", exits gracefully.

- **Stale Lock: Instance crashed with outbox event locked** — `OutboxEventProcessor.resetStaleEvents()` marks events as PENDING if `locked_until < now - 30s`. Prevents permanent lock.

- **Long Job Execution: Job takes 10 minutes, next trigger in 5 seconds** — Quartz threads from thread pool. Job completes, then next trigger fires. No missed executions if thread pool is sized correctly.

---

## Known Issues / Limitations

1. **No Job Result Persistence** — Job execution results (e.g., "100 invoices generated") are logged but not stored in database. Historical reporting requires parsing logs.

2. **No Job Priority** — All jobs share same thread pool. If `BillingCycleJob` takes 30 minutes, `TrialExpiryJob` waits. Consider separate thread pools for high-priority jobs.

3. **Trial Expiry Auto-Downgrade Not Implemented** — When trial expires, subscription status becomes PAST_DUE (not downgraded to Free). Spec requires TrialExpiryJob to support optional auto-downgrade flag.

4. **Payment Retry Backoff Hardcoded** — Retry durations (1s, 2s, 4s, 8s, 5min) are hardcoded in `PaymentService`. Should be configurable via application.yaml.

5. **No Job Monitoring Metrics** — No Micrometer metrics published for job execution time, success rate, or tenant processing duration. Add custom metrics for observability.

6. **OutboxEventProcessor Does Not Handle Kafka/Async Channels** — Events published synchronously to `ApplicationEventPublisher`. If notification channel is Kafka (async), event may not be published by time method returns. Consider async event publishing.

---

## Future Improvements

1. **Separate Thread Pools by Job Priority** — Configure `@Bean ThreadPoolTaskExecutor` with separate pool for critical jobs (TrialExpiry, BillingCycle) vs. lower-priority jobs (TrialEndingSoon).

2. **Historical Job Metrics** — Store job execution results (start time, end time, tenant count, error count) in `job_execution_history` table. Enable reporting: "Which tenants had billing errors in past 30 days?"

3. **Configurable Retry Backoff** — Move hardcoded retry durations to `application.yaml`. Allow operators to adjust retry strategy without code change.

4. **Job Pause/Resume via Admin API** — Add endpoint `/admin/jobs/{jobName}/pause` to stop job temporarily (e.g., during maintenance). Persists pause state in database.

5. **Tenant-Specific Job Scheduling** — Allow tenants to customize job behavior (e.g., "charge on 15th of month, not 1st"). Store job config in tenant schema, apply in job.

6. **Event Dead-Letter Queue** — Track events that failed all retries. Move to dead-letter queue for manual inspection and reprocessing.

7. **Job Execution Observability** — Publish Micrometer metrics: `job.execution.duration`, `job.execution.count`, `job.execution.errors`. Enable monitoring dashboards.

---

## Related Documents

- [retry-mechanisms.md](./retry-mechanisms.md) — Detailed backoff/retry strategies across system
- [outbox-pattern.md](../01-architecture/outbox-pattern.md) — Transactional outbox reliability guarantee
- [subscription-lifecycle.md](../05-platform-billing/subscription-lifecycle.md) — Subscription state machine and transitions
- [invoice.md](../05-platform-billing/invoice.md) — Invoice domain object and lifecycle
- [payment-processing.md](../05-platform-billing/payment-processing.md) — Payment charge flow and failure modes
- [cross-tenant-safety.md](../02-multi-tenancy/cross-tenant-safety.md) — TenantContext lifecycle and data isolation
