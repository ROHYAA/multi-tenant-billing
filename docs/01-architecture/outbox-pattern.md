---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - events
  - reliability
  - transactions
  - idempotency
  - scaling
  - outbox
related_documents:
  - ./event-flow.md
  - ../05-platform-billing/invoice.md
---

# Outbox Pattern

## Executive Summary

The **transactional outbox pattern** ensures reliable at-least-once event delivery in distributed systems. When a tenant triggers a domain event (e.g., invoice created), instead of publishing immediately (risking loss if the transaction rolls back), the event is saved to an `outbox_events` table in the same transaction. A background scheduler (`OutboxEventProcessor`) periodically polls pending events, publishes them, and marks them processed. Idempotency keys prevent duplicate event processing. Distributed locks (`locked_until`) prevent multiple instances from processing the same event. Without this pattern, event loss or duplication would corrupt billing, customer data, and audit logs.

---

## Context / Problem

### Why Not Direct Event Publishing?

```
WRONG (Direct publish):
┌──────────────────────────────┐
│ BEGIN TRANSACTION            │
│ 1. INSERT INTO invoices      │ ← Success
│ 2. ApplicationEventPublisher │ ← Publish event
│    .publishEvent(created)    │
│ 3. COMMIT                    │ ← BOOM! Database crash
└──────────────────────────────┘
Event published but transaction rolled back.
Invoice not created; event subscribers confused.
```

Application event is published **before transaction commits**. If the transaction rolls back (due to exception, deadlock, or DB crash), the event has already been published but the invoice doesn't exist. External systems act on a ghost event.

### Why Transactional Outbox?

```
RIGHT (Transactional Outbox):
┌──────────────────────────────────────┐
│ BEGIN TRANSACTION                    │
│ 1. INSERT INTO invoices              │ ← Success
│ 2. INSERT INTO outbox_events (invoice_created) │
│ 3. COMMIT                            │
│ Event stored with invoice in same tx │
└──────────────────────────────────────┘
         ↓ (even if everything crashes here, event is persisted)
┌──────────────────────────────────────┐
│ Background Scheduler (5 sec later)   │
│ SELECT * FROM outbox_events WHERE status='PENDING'
│ Publish events                       │
│ UPDATE outbox_events SET status='PROCESSED'
└──────────────────────────────────────┘
```

Event is saved **within the same transaction** as the business data. If the transaction rolls back, the event is gone too (consistent state). If it commits, the event is guaranteed to exist. The scheduler will always find and publish pending events, even if the app crashed before publishing.

### Why Idempotency Keys?

The scheduler might process the same event twice:
1. Event 1 published to message queue
2. App crashes before updating `status = PROCESSED`
3. Scheduler restarts, finds event 1 still PENDING
4. Event 1 published again → **duplicate**

Idempotency keys prevent duplicates: `aggregateType:aggregateId:eventType:date` ensures the same business event (e.g., invoice 456 created on 2026-04-24) is only processed once, even if published multiple times.

### Why Distributed Locks?

In a horizontally scaled deployment (multiple app instances):
1. Instance A finds event 1 (PENDING)
2. Instance B finds event 1 (PENDING) simultaneously
3. Both publish event 1 → **duplicate again**

Distributed locks (`FOR UPDATE SKIP LOCKED` in SQL) ensure only one instance can lock and process an event at a time.

---

## Dependencies

### Inbound (Who Creates Outbox Events)
- `InvoiceService` → `OutboxEventPublisher.save(InvoiceCreatedEvent)` — On invoice creation
- `SubscriptionService` → `OutboxEventPublisher.save(SubscriptionActivatedEvent)` — On subscription changes
- `PaymentService` → `OutboxEventPublisher.save(PaymentCapturedEvent)` — On payment success
- `AuthService` → `OutboxEventPublisher.save(UserCreatedEvent)` — On user signup
- Any domain service that triggers an event

### Outbound (Who Processes Outbox Events)
- `OutboxEventProcessor` (scheduled job) → `OutboxEventRepository.findPendingForUpdate()` — Poll events
- `OutboxEventProcessor` → `ApplicationEventPublisher.publishEvent()` — Publish to Spring event listeners
- `OutboxEventProcessor` → `OutboxEventRepository.save()` — Update status to PROCESSED
- Notification service, audit service, billing service listen to events

### Configuration
- `app.outbox.batch-size: 100` — Number of events to process per poll
- `app.outbox.poll-interval-ms: 5000` — How often to check for pending events (5 sec)
- `app.outbox.max-retries: 5` — Max retry attempts before marking FAILED

---

## Design / Implementation

### Outbox Event Table Schema

```sql
CREATE TABLE outbox_events (
    id                  BIGSERIAL PRIMARY KEY
    status              VARCHAR(20)   -- PENDING, PROCESSING, PROCESSED, FAILED
    event_type          VARCHAR(100)  -- "InvoiceCreated", "PaymentCaptured"
    aggregate_type      VARCHAR(100)  -- "Invoice", "Payment" (entity type)
    aggregate_id        VARCHAR(100)  -- "456" (entity ID)
    payload             TEXT          -- JSON: full event data serialized
    event_class         VARCHAR(255)  -- Java class name for deserialization
    idempotency_key     VARCHAR(255)  -- "Invoice:456:Created:2026-04-24"
    retry_count         INTEGER       -- Current retry attempt (0-5)
    max_retries         INTEGER       -- Maximum retries allowed
    last_error          TEXT          -- Error message from failed attempt
    processed_at        TIMESTAMPTZ   -- When successfully published
    locked_until        TIMESTAMPTZ   -- Distributed lock expiry time
    created_at          TIMESTAMPTZ   -- When event was queued
    updated_at          TIMESTAMPTZ   -- Last update
);

-- Efficient polling index
CREATE INDEX idx_outbox_status_locked 
    ON outbox_events (status, locked_until) 
    WHERE status IN ('PENDING', 'PROCESSING');
```

### Event Lifecycle

```
1. Domain Event Created
   └─ InvoiceService.create(request)
   └─ invoice = new Invoice(...)
   └─ invoiceRepository.save(invoice)  ← INSERT into invoices table
   └─ event = new InvoiceCreatedEvent(invoice)
   └─ outboxEventPublisher.save(event)  ← INSERT into outbox_events
   └─ COMMIT (both in same transaction)

2. PENDING State (at rest in DB)
   └─ status = PENDING
   └─ locked_until = null
   └─ Can be picked up by scheduler

3. Polling (every 5 seconds)
   └─ OutboxEventProcessor.processOutbox()
   └─ SELECT * FROM outbox_events 
      WHERE status IN ('PENDING', 'PROCESSING')
      AND locked_until IS NULL OR locked_until < NOW()
      ORDER BY created_at ASC
      LIMIT 100
      FOR UPDATE SKIP LOCKED  ← Distributed lock

4. PROCESSING State (being published)
   └─ UPDATE outbox_events 
      SET status = 'PROCESSING', 
          locked_until = NOW() + 30 seconds
      WHERE id = ?
   └─ locked_until = now + 30s prevents another instance from picking it up
   └─ Publish event to ApplicationEventPublisher
   └─ Event reaches all Spring event listeners

5. PROCESSED State (success)
   └─ UPDATE outbox_events 
      SET status = 'PROCESSED', 
          processed_at = NOW(),
          locked_until = null
      WHERE id = ?
   └─ Event is marked complete

6. FAILED State (after max retries)
   └─ If publishing fails 5 times (max-retries = 5)
   └─ UPDATE outbox_events 
      SET status = 'FAILED', 
          last_error = 'Connection timeout to queue'
   └─ Manual intervention required
```

### Idempotent Event Processing

**Idempotency Key Format:**
```
"{aggregateType}:{aggregateId}:{eventType}:{date}"

Examples:
- "Invoice:456:Created:2026-04-24"
- "Subscription:789:Upgraded:2026-04-24"
- "Payment:321:Captured:2026-04-24"
```

**Duplicate Detection:**
```
1. Event 1 arrives (Invoice 456 created on 2026-04-24)
   └─ idempotencyKey = "Invoice:456:Created:2026-04-24"
   └─ Check: SELECT COUNT(*) FROM outbox WHERE idempotency_key = ?
   └─ Result: 0 (not found)
   └─ INSERT into outbox_events
   └─ published = true

2. Event 1 arrives AGAIN (duplicate from retry mechanism)
   └─ idempotencyKey = "Invoice:456:Created:2026-04-24" (same!)
   └─ Check: SELECT COUNT(*) FROM outbox WHERE idempotency_key = ?
   └─ Result: 1 (found!)
   └─ SKIP INSERT (idempotency prevents duplicate)
   └─ Log: "Duplicate event skipped"

3. Different event on same invoice (Invoice 456 updated on 2026-04-24)
   └─ idempotencyKey = "Invoice:456:Updated:2026-04-24" (different!)
   └─ Check: SELECT COUNT(*) FROM outbox WHERE idempotency_key = ?
   └─ Result: 0 (not found)
   └─ INSERT into outbox_events (both created + updated events now exist)
```

### Distributed Lock Mechanism

**Problem**: Multiple app instances running (e.g., Kubernetes pods scaled to 3).

```
Instance A: SELECT * FROM outbox_events WHERE status='PENDING' LIMIT 100
Instance B: SELECT * FROM outbox_events WHERE status='PENDING' LIMIT 100
Instance C: SELECT * FROM outbox_events WHERE status='PENDING' LIMIT 100

All three see Event 1 (PENDING)
All three try to publish Event 1 → Duplicate

Solution: FOR UPDATE SKIP LOCKED
```

**How it works:**

```sql
-- Only ONE instance can acquire this lock
SELECT * FROM outbox_events 
WHERE status IN ('PENDING', 'PROCESSING')
AND (locked_until IS NULL OR locked_until < NOW())
ORDER BY created_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;  ← Request exclusive row lock

-- Instance A: Acquires lock, UPDATE locked_until
UPDATE outbox_events 
SET status = 'PROCESSING', 
    locked_until = NOW() + INTERVAL '30 seconds'
WHERE id IN (456, 789, 321);

-- Instance B: Executes same SELECT
-- FOR UPDATE SKIP LOCKED skips the locked rows (returns 0 rows if all locked)
-- Or picks up remaining unlocked rows

-- Instance A publishes events, updates status = PROCESSED
-- Instance B never sees these rows (no duplicates)
```

**Lock Recovery** (if Instance A crashes):
```
Event locked_until = NOW() + 30 seconds
Instance A crashes
30 seconds pass
Instance B polls: locked_until < NOW() is TRUE
Instance B acquires lock, retries publishing
(Called "stale lock recovery")
```

### OutboxEventPublisher (Saving Events)

```java
@Service
public class OutboxEventPublisher {
    
    public void save(DomainEvent event, String aggregateType, Object aggregateId) {
        // Build idempotency key
        String idempotencyKey = aggregateType + ":" + aggregateId + ":" 
                              + event.getEventType() + ":" + LocalDate.now();
        
        // Check if already exists (duplicate prevention)
        if (outboxRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.debug("Duplicate event skipped: {}", idempotencyKey);
            return;  // Don't insert again
        }
        
        // Create outbox event
        OutboxEvent outbox = OutboxEvent.builder()
            .eventType(event.getEventType())
            .aggregateType(aggregateType)
            .aggregateId(aggregateId.toString())
            .eventClass(event.getClass().getName())
            .payload(objectMapper.writeValueAsString(event))  // JSON
            .idempotencyKey(idempotencyKey)
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .maxRetries(5)
            .build();
        
        outboxRepository.save(outbox);  // INSERT into same transaction
    }
}
```

**Usage in domain service:**

```java
@Service
@Transactional
public class InvoiceService {
    
    private final InvoiceRepository invoiceRepository;
    private final OutboxEventPublisher outboxPublisher;
    
    public void createInvoice(InvoiceRequest request) {
        // Step 1: Create invoice (INSERT)
        Invoice invoice = Invoice.builder()
            .amount(request.getAmount())
            .status(Status.DRAFT)
            .build();
        Invoice saved = invoiceRepository.save(invoice);
        
        // Step 2: Publish event (INSERT into outbox)
        InvoiceCreatedEvent event = new InvoiceCreatedEvent(
            invoiceId,  aggregateId
            tenantId,   context
            amount,     data
            createdAt
        );
        outboxPublisher.save(event, "Invoice", saved.getId());
        
        // Step 3: COMMIT (both inserts committed together)
        // If exception here, both rolled back (atomic)
    }
}
```

### OutboxEventProcessor (Publishing Events)

Scheduled job runs every 5 seconds across all tenant schemas:

```java
@Component
public class OutboxEventProcessor {
    
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    public void processOutbox() {
        // Step 1: Iterate all active tenants
        List<Tenant> tenants = tenantRepository.findAllByStatus(ACTIVE);
        
        for (Tenant tenant : tenants) {
            try {
                // Step 2: Set TenantContext (important for schema isolation)
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());
                
                processOutboxForTenant();
                
            } finally {
                TenantContext.clear();
            }
        }
    }
    
    private void processOutboxForTenant() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            
            // Step 3: Recover stale locks (locks older than 30 seconds)
            int recovered = outboxRepository.resetStaleEvents(now.minus(Duration.ofSeconds(30)));
            if (recovered > 0) {
                log.info("Recovered {} stale events", recovered);
            }
            
            // Step 4: Poll pending events with distributed lock
            List<OutboxEvent> events = outboxRepository.findPendingForUpdate(now, 100);
            // SQL: SELECT * FROM outbox_events 
            //      WHERE status IN ('PENDING', 'PROCESSING')
            //      AND (locked_until IS NULL OR locked_until < NOW())
            //      FOR UPDATE SKIP LOCKED
            //      LIMIT 100
            
            for (OutboxEvent event : events) {
                try {
                    // Step 5: Mark as PROCESSING (lock acquired)
                    event.markProcessing();  // status=PROCESSING, locked_until=now+30s
                    outboxRepository.save(event);
                    
                    // Step 6: Publish to Spring event listeners
                    Object deserializedEvent = objectMapper.readValue(
                        event.getPayload(), 
                        Class.forName(event.getEventClass())
                    );
                    applicationEventPublisher.publishEvent(deserializedEvent);
                    
                    // Step 7: Mark as PROCESSED (success)
                    event.markProcessed();  // status=PROCESSED, processed_at=now
                    outboxRepository.save(event);
                    
                    log.info("Event processed: type={}, aggregateId={}", 
                        event.getEventType(), event.getAggregateId());
                    
                } catch (Exception e) {
                    // Step 8: Handle failure (retry with backoff)
                    event.incrementRetryCount();
                    
                    if (event.getRetryCount() >= event.getMaxRetries()) {
                        // Max retries exceeded
                        event.markFailed(ErrorType.MAX_RETRIES_EXCEEDED, e.getMessage());
                        log.error("Event failed after {} retries: {}", 
                            event.getMaxRetries(), e.getMessage());
                    } else {
                        // Schedule retry (exponential backoff)
                        long backoffSeconds = Math.min(300, (long)Math.pow(2, event.getRetryCount()));
                        event.scheduleRetry(backoffSeconds);
                        log.warn("Event retry scheduled: attempt={}/{}, backoff={}s", 
                            event.getRetryCount(), event.getMaxRetries(), backoffSeconds);
                    }
                    
                    outboxRepository.save(event);
                }
            }
        });
    }
}
```

### Event Listeners (Downstream)

Spring `@EventListener` beans listen for published events:

```java
@Component
@Slf4j
public class NotificationEventListener {
    
    @EventListener
    public void onInvoiceCreated(InvoiceCreatedEvent event) {
        log.info("Invoice created: id={}", event.getInvoiceId());
        // Send email to tenant owner
        emailService.sendInvoiceCreatedNotification(event);
    }
}

@Component
@Slf4j
public class AuditEventListener {
    
    @EventListener
    public void onAnyEvent(DomainEvent event) {
        log.info("Audit: event={}, aggregateId={}", 
            event.getEventType(), event.getAggregateId());
        // Write to audit log
        auditService.logEvent(event);
    }
}
```

---

## Flow

See Event Lifecycle ASCII art above.

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `OutboxEventPublisher` | [BIL-35] | `save(event, aggregateType, aggregateId)` | Save event to outbox table; check idempotency |
| `OutboxEventPublisher` | [BIL-35] | `buildIdempotencyKey()` | Create unique key to prevent duplicates |
| `OutboxEventPublisher` | [BIL-35] | `serialize(event)` | Convert event to JSON for persistence |
| `OutboxEventProcessor` | [BIL-30] | `processOutbox()` | Scheduled job that iterates tenants |
| `OutboxEventProcessor` | [BIL-30] | `processOutboxForTenant()` | Poll + publish events for one tenant |
| `OutboxEvent` | [SHARED-X] | `markProcessing()` | Set status=PROCESSING, lock with 30s timeout |
| `OutboxEvent` | [SHARED-X] | `markProcessed()` | Set status=PROCESSED; event is published |
| `OutboxEvent` | [SHARED-X] | `scheduleRetry()` | Set status=PENDING, lock with exponential backoff |
| `OutboxEvent` | [SHARED-X] | `markFailed()` | Set status=FAILED; manual intervention needed |

---

## Rules / Constraints

1. **All domain events MUST be saved to outbox within the same @Transactional method** — If outbox save is in a separate transaction or before commit, atomicity is lost. Insert invoice AND insert outbox event in one BEGIN/COMMIT block.

2. **Idempotency keys MUST be deterministic** — Same event must generate the same key. Use: `aggregateType:aggregateId:eventType:date` (never randomUUID). Keys enable duplicate detection.

3. **Outbox events MUST NOT be deleted, only marked as PROCESSED** — History tracking requires events to persist. Soft-delete via `status=PROCESSED` or archive to separate history table after 30 days.

4. **Scheduler MUST use FOR UPDATE SKIP LOCKED** — Prevents multiple instances from processing the same event. Without distributed lock, horizontal scaling causes duplicates.

5. **Event listeners MUST be idempotent** — If an event is published twice (due to retry), the listener should produce the same result. Example: "Send email" should check if already sent before re-sending.

6. **Retry logic MUST include exponential backoff** — Linear retries (every 5s) can overwhelm failing services. Use: 2^retryCount seconds (1s, 2s, 4s, 8s, ..., capped at 5min).

7. **Failed events MUST alert operators** — When status=FAILED after max retries, send alert to ops team. Event publishing failure indicates a serious problem (queue down, serialization bug, etc.).

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| Event save fails (DB error) | `JdbcException` | 500 Internal Server Error | Transaction rolled back; invoice not created; no event leaked |
| Event listener throws exception | `EventPublicationException` | Scheduler catches; event retried | Listener must handle exceptions; retry until success or max attempts |
| Queue/external service down | `TimeoutException` during publish | Scheduler marks FAILED after 5 retries; alert ops | Restart queue/service; manually retry events via admin API |
| Idempotency check fails (DB error) | `SQLException` | 500 Internal Server Error | Duplicate events might accumulate; monitor |
| Distributed lock acquired but instance crashes | Stale lock (locked_until > now) | N/A (background) | Stale lock recovery (30s) finds and retries event |
| Outbox table full / disk space exhausted | `DiskSpaceException` | 500 to request that created event | Purge old processed events; restart event insertion |
| Event payload too large for TEXT column | `DataException` | 500 to original request | Increase payload column size or externalize payload to object storage |

---

## Edge Cases

- **Concurrency**: Multiple threads in same instance simultaneously publish events. Outbox commits are serialized by database transaction locks; no race condition. Idempotency ensures no duplicates even if same event is published twice in parallel.

- **Timezone**: Event `created_at` timestamps are in UTC (TIMESTAMPTZ). Idempotency key uses `LocalDate.now()` which is server timezone. This is OK (date is just for scoping key); UTC timestamps are used elsewhere.

- **Tenant Isolation**: Events are stored per-tenant schema (`s_456.outbox_events`). When scheduler processes, it sets TenantContext per tenant. No cross-tenant event leakage.

- **Event Ordering**: Outbox guarantees at-least-once delivery, NOT ordered delivery. If Invoice Created and Invoice Updated events are published simultaneously, they might be processed out of order. Listeners must handle out-of-order events. If ordering is critical, use a message queue with partitioning.

- **Subscriber Failure**: If a listener throws an exception, the entire event publication fails (Spring event dispatch is synchronous). Event is marked PROCESSING but not PROCESSED; scheduler retries. This is correct (fail-fast + retry).

- **Null aggregateId**: Events without an aggregate (e.g., system health check) can use `aggregateType=null, aggregateId=null`. Idempotency key becomes `null` (not stored). `saveWithoutIdempotency()` method is available for such events.

---

## Known Issues / Limitations

1. **Event subscribers run synchronously in scheduler thread** — If a listener is slow (e.g., sends email), the scheduler blocks. All events for that tenant are delayed. Workaround: Listeners should be async (`@Async` or queue event to separate thread pool).

2. **No event ordering guarantees** — Events are processed in FIFO order (by `created_at`), but there's no guarantee that Invoice Created is processed before Invoice Updated if they're stored in different transactions. Use event versioning in domain model if ordering matters.

3. **Outbox table grows unbounded** — After 1 year, millions of PROCESSED rows accumulate. No automatic archival/purge. Operator must manually clean old events (e.g., `DELETE FROM outbox_events WHERE status='PROCESSED' AND processed_at < NOW() - INTERVAL '30 days'`).

4. **No event replay mechanism** — If a listener is added after events are published, it cannot replay historical events. Events are not stored indefinitely. To replay, save events to a separate event store.

5. **Max retries per event is global** — All events retry 5 times. Some events might need more retries (network flakes) while others should fail fast (data validation errors). Per-event retry policies not yet supported.

---

## Future Improvements

1. Implement event replay API — Admin endpoint to re-publish a closed event by ID. Allows adding new listeners to historical events.

2. Add event dead-letter queue (DLQ) — Failed events after max retries go to DLQ table. Separate scheduler processes DLQ with longer retry intervals (hours instead of seconds).

3. Implement event filtering/routing — Route events to different handlers based on event type or aggregate. Currently all listeners receive all events.

4. Add event schema versioning — Events evolve over time (fields added/removed). Store schema version in event; deserializers handle multiple versions.

5. Implement distributed event outbox across multiple databases — For sharded tenants across multiple PostgreSQL instances, use a central coordinator to track which tenant processors have processed which events.

---

## Related Documents
- [event-flow.md](./event-flow.md) — Full event-driven architecture (outbox is one piece)
- [request-flow.md](./request-flow.md) — HTTP request lifecycle; where events are published
- [invoice.md](../05-platform-billing/invoice.md) — Example event: InvoiceCreatedEvent
- [system-design.md](./system-design.md) — Architecture overview
