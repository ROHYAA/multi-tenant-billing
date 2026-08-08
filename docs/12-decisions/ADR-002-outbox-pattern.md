# ADR-002: Transactional Outbox Pattern for Reliable Event Publishing

**Date:** May 2026  
**Status:** Accepted  
**Decision Maker:** Architecture Team  
**Stakeholders:** Backend Team, Payments, Notifications

---

## Problem Statement

The system needs to publish domain events reliably when business actions occur (e.g., "SubscriptionCreated", "PaymentCaptured"). However, **distributed systems face a challenge:**

> If you update the database AND publish an event, what if one succeeds and the other fails?

**Scenarios (without Outbox):**

```
Scenario 1: DB succeeds, event publish fails
└─ Subscription created ✅
└─ Notification NOT sent ❌
└─ Customer doesn't know about subscription

Scenario 2: Event publish succeeds, DB fails
└─ Notification sent ✅
└─ Subscription creation rolled back ❌
└─ Inconsistent state: notification but no subscription

Scenario 3: Network timeout during event publishing
└─ Unknown if event was published
└─ Duplicate event? Missing event?
└─ No way to reconcile
```

**Requirements:**
- ✅ Exactly one event per business action (no duplicates, no losses)
- ✅ Events delivered at least once (retries on failure)
- ✅ Atomic with database transaction (all-or-nothing)
- ✅ Works with asynchronous handlers
- ✅ Survives application crashes
- ✅ No third-party distributed transaction coordinator (complex, costly)

---

## Options Evaluated

### Option 1: Direct Event Publishing (Naive)

```java
// ❌ WRONG: Two separate operations, either can fail independently
@Transactional
public Subscription create(CreateSubscriptionRequest request) {
    // Operation 1: Update database
    Subscription subscription = new Subscription(...);
    subscriptionRepository.save(subscription);
    // ✅ Database transaction commits
    
    // Operation 2: Publish event (OUTSIDE transaction)
    eventPublisher.publish(new SubscriptionCreatedEvent(...));
    // ❌ Could fail or timeout
}
```

**Problems:**
- ❌ No atomicity guarantee (DB succeeds, publish fails)
- ❌ No failure recovery (crashed app loses event)
- ❌ No deduplication (could send duplicate if retried)

---

### Option 2: Distributed Transactions (Two-Phase Commit)

```java
// Use 2PC (e.g., Narayana, Seata)
@Transactional
public Subscription create(CreateSubscriptionRequest request) {
    subscriptionRepository.save(subscription);      // XA transaction
    eventPublisher.publish(event);                  // XA transaction
    // Both succeed or both roll back
}
```

**Pros:**
- ✅ True atomicity across systems

**Cons:**
- ❌ Complex infrastructure (2PC coordinator)
- ❌ Performance impact (locking, coordination overhead)
- ❌ Operational burden (debugging failures, recovery)
- ❌ Not recommended for modern systems (CAP theorem)
- ❌ Blocks scalability

---

### Option 3: Transactional Outbox Pattern (SELECTED)

```java
// ✅ CORRECT: Single database transaction
@Transactional
public Subscription create(CreateSubscriptionRequest request) {
    // Operation 1: Save subscription
    Subscription subscription = subscriptionRepository.save(
        new Subscription(...)
    );
    
    // Operation 2: Save event to outbox table (SAME transaction)
    outboxEventRepository.save(
        OutboxEvent.builder()
            .aggregateId(subscription.getId())
            .aggregateType("Subscription")
            .eventType("SubscriptionCreated")
            .payload(event)
            .status(OutboxStatus.PENDING)
            .build()
    );
    // ✅ Both saved atomically in one transaction
}

// Separate process: Outbox Poller
@Scheduled(fixedDelay = 1000)
public void publishPendingEvents() {
    List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatus(PENDING);
    
    for (OutboxEvent event : pendingEvents) {
        try {
            // Publish event
            eventPublisher.publish(event.getPayload());
            
            // Mark as published
            event.setStatus(OutboxStatus.PUBLISHED);
            outboxEventRepository.save(event);
        } catch (Exception e) {
            // Retry next poll cycle
            log.warn("Failed to publish event: {}", event.getId());
        }
    }
}
```

**Pros:**
- ✅ Atomicity guaranteed (single transaction)
- ✅ At-least-once delivery (with retries)
- ✅ Survives application crashes (outbox persisted)
- ✅ Simple implementation (no 2PC complexity)
- ✅ Built on standard relational database
- ✅ Easy to debug (events visible in database)

**Cons:**
- ❌ Events published with delay (polling latency)
- ❌ Requires polling infrastructure (separate job)
- ❌ Duplicate events possible (retry without deduplication)

---

## Decision

**SELECTED: Option 3 — Transactional Outbox Pattern**

### Rationale

1. **Reliability** — Atomic with database, no lost events
2. **Simplicity** — Works with standard PostgreSQL, no special tooling
3. **Scalability** — Polling-based, not coordinator-based
4. **Observability** — Events visible in database for debugging
5. **Proven Pattern** — Used by Uber, Netflix, Stripe

### Trade-offs Accepted

- **Latency** — Events published after polling delay (1-5 seconds)
- **Duplicates** — Consumer must be idempotent
- **Polling Overhead** — Continuous database poll for events

---

## Implementation

### 1. Outbox Event Entity

```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long aggregateId;  // e.g., subscription_id
    
    @Column(nullable = false, length = 50)
    private String aggregateType;  // e.g., "Subscription"
    
    @Column(nullable = false, length = 50)
    private String eventType;  // e.g., "SubscriptionCreated"
    
    @Column(nullable = false, columnDefinition = "TEXT")
    @Convert(converter = JsonConverter.class)
    private DomainEvent payload;  // Event data (JSON)
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;  // PENDING, PUBLISHED, FAILED
    
    @Column(nullable = false)
    private Integer retryCount;  // 0, 1, 2, ... (max 3)
    
    @Column
    private String lastErrorMessage;
    
    @CreationTimestamp
    private Instant createdAt;
    
    @UpdateTimestamp
    private Instant updatedAt;
}

public enum OutboxStatus {
    PENDING,     // Not yet published
    PUBLISHED,   // Successfully published
    FAILED       // Failed after max retries
}
```

### 2. Domain Event Publish (Within Transaction)

```java
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {
    
    private final SubscriptionRepository subscriptionRepository;
    private final OutboxEventRepository outboxEventRepository;
    
    public Subscription create(CreateSubscriptionRequest request) {
        // Step 1: Create subscription
        Subscription subscription = new Subscription();
        subscription.setPlanId(request.getPlanId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartDate(LocalDate.now());
        
        Subscription saved = subscriptionRepository.save(subscription);
        
        // Step 2: Create and save event to outbox (SAME transaction)
        DomainEvent event = new SubscriptionCreatedEvent(
            saved.getId(),
            saved.getPlanId(),
            saved.getStatus(),
            saved.getStartDate()
        );
        
        OutboxEvent outboxEvent = OutboxEvent.builder()
            .aggregateId(saved.getId())
            .aggregateType("Subscription")
            .eventType("SubscriptionCreated")
            .payload(event)
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .build();
        
        outboxEventRepository.save(outboxEvent);
        
        // ✅ Both changes committed together
        return saved;
    }
}
```

### 3. Outbox Poller (Scheduled Job)

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPollerService {
    
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher springEventPublisher;
    private final TenantService tenantService;
    
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)  // Poll every 1 second
    @Transactional
    public void pollAndPublishEvents() {
        // Fetch pending events
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
            OutboxStatus.PENDING
        );
        
        for (OutboxEvent event : pendingEvents) {
            try {
                // Set tenant context from event
                Long tenantId = event.getTenantId();
                Tenant tenant = tenantService.getById(tenantId);
                TenantContext.setTenantId(tenantId);
                TenantContext.setCurrentSchema(tenant.getSchemaName());
                
                // Publish event to Spring event listeners (async)
                springEventPublisher.publishEvent(event.getPayload());
                
                // Mark as published
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setUpdatedAt(Instant.now());
                outboxEventRepository.save(event);
                
                log.info("Published event: {} of type {}", 
                    event.getId(), event.getEventType());
                
            } catch (Exception e) {
                // Handle retry logic
                handlePublishFailure(event, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
    
    private void handlePublishFailure(OutboxEvent event, Exception e) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastErrorMessage(e.getMessage());
        
        if (event.getRetryCount() >= 3) {
            // Max retries exceeded, mark as failed
            event.setStatus(OutboxStatus.FAILED);
            log.error("Event {} failed after 3 retries: {}", 
                event.getId(), e.getMessage());
        }
        // Else: Keep as PENDING for next poll cycle
        
        outboxEventRepository.save(event);
    }
}
```

### 4. Event Handler (Idempotent)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionEventHandler {
    
    private final NotificationService notificationService;
    private final OutboxEventRepository outboxEventRepository;
    
    @EventListener
    @Async  // Run in background
    public void handleSubscriptionCreated(SubscriptionCreatedEvent event) {
        try {
            log.info("Handling SubscriptionCreated: subscription={}", event.getSubscriptionId());
            
            // ✅ IDEMPOTENT: Safe to call multiple times
            // Check if notification already sent
            boolean alreadySent = outboxEventRepository.existsByPayloadAndStatus(
                event, OutboxStatus.PUBLISHED
            );
            
            if (alreadySent) {
                log.info("Notification already sent for subscription {}, skipping", 
                    event.getSubscriptionId());
                return;
            }
            
            // Send notification
            notificationService.send(SendEmailRequest.builder()
                .to(event.getUserEmail())
                .template("subscription-created")
                .variables(Map.of(
                    "subscriptionId", event.getSubscriptionId(),
                    "planName", event.getPlanName()
                ))
                .build());
            
        } catch (Exception e) {
            log.error("Error handling SubscriptionCreated event", e);
            // Don't rethrow; let poller handle retry
        }
    }
}
```

### 5. Database Schema (Flyway Migration)

```sql
-- V1.0__Create_Outbox_Events_Table.sql

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    
    aggregate_id BIGINT NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    
    payload JSONB NOT NULL,  -- Store event as JSON
    
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, PUBLISHED, FAILED
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error_message TEXT,
    
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    
    -- Indexes for polling efficiency
    INDEX idx_status_created (status, created_at),
    INDEX idx_aggregate (aggregate_type, aggregate_id)
);

-- Archive old events after 30 days
CREATE TABLE outbox_events_archive LIKE outbox_events;
```

---

## Event Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Business Action (Create Subscription)                      │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────────┐
│  @Transactional service.create()                            │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ 1. INSERT into subscriptions table                      ││
│  │    (status=ACTIVE, startDate=today, ...)               ││
│  │                                                         ││
│  │ 2. INSERT into outbox_events table                      ││
│  │    (eventType=SubscriptionCreated, status=PENDING)     ││
│  │                                                         ││
│  │ 3. COMMIT (atomic)                                      ││
│  └─────────────────────────────────────────────────────────┘│
└──────────────────┬──────────────────────────────────────────┘
                   │
                   ▼
        ✅ Changes persisted
                   │
                   ▼ (1-5 second delay)
┌─────────────────────────────────────────────────────────────┐
│  OutboxEventPollerService (scheduled job)                   │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ 1. SELECT * FROM outbox_events WHERE status=PENDING    ││
│  │                                                         ││
│  │ 2. For each event:                                      ││
│  │    a. springEventPublisher.publishEvent(payload)       ││
│  │    b. UPDATE outbox_events SET status=PUBLISHED        ││
│  │                                                         ││
│  │ 3. If error: increment retry_count                     ││
│  │    (Keep status=PENDING for next poll)                 ││
│  └─────────────────────────────────────────────────────────┘│
└──────────────────┬──────────────────────────────────────────┘
                   │
         ┌─────────┴─────────┐
         │                   │
         ▼                   ▼
    Async Handler #1    Async Handler #2
    (Email Service)     (Webhook Service)
    
    @EventListener
    handleSubscriptionCreated()
    → Send email notification
    
    (Each handler idempotent)
```

---

## Guarantees & Limitations

### Guarantees

✅ **At-Least-Once Delivery**
- Event published to all subscribers at least once
- Possible duplicates (if poller retries)

✅ **No Lost Events**
- Persisted to database before polling
- Survives application crash

✅ **Atomic with Business Logic**
- Event creation same transaction as entity creation
- All-or-nothing guarantee

✅ **Order Preserved Per Aggregate**
- Events ordered by createdAt
- Subscription events in order (by ID)

### Limitations

❌ **Not Exactly-Once** (but at-least-once)
- Consumers must handle duplicates
- Idempotency required in handlers

❌ **Eventual Consistency**
- Events published with delay (polling latency, default 1 second)
- Not suitable for real-time, synchronous guarantees

❌ **No Cross-Tenant Ordering**
- Events within tenant ordered, but not globally
- (Not needed for MTBS architecture)

---

## Deduplication Strategy

### Idempotency Key

```java
// Email handler: Check if already sent
@EventListener
public void handleSubscriptionCreated(SubscriptionCreatedEvent event) {
    // Idempotency key: (subscription_id, event_type)
    String idempotencyKey = event.getSubscriptionId() + "::" + "SubscriptionCreated";
    
    // Check if already processed
    if (notificationService.wasAlreadySent(idempotencyKey)) {
        return;  // Skip duplicate
    }
    
    // Process event
    notificationService.sendEmail(event);
    
    // Mark as processed
    notificationService.markAsSent(idempotencyKey);
}
```

### Database-Level Deduplication

```sql
-- Unique constraint on (aggregate_id, event_type, created_at)
-- Prevents duplicate events for same aggregate in same second
ALTER TABLE outbox_events
ADD UNIQUE (aggregate_type, aggregate_id, event_type, created_at);
```

---

## Monitoring & Alerting

### Metrics to Track

| Metric | Alert Threshold | Purpose |
|--------|------------------|---------|
| **Pending Events** | > 1000 | Poller backlog |
| **Failed Events** | > 10 | Persistent failures |
| **Publish Latency** | > 5s | P99 latency |
| **Retry Rate** | > 10% | Stability issue |

### Example Alert

```yaml
alert: OutboxEventBacklog
expr: count(outbox_events{status="PENDING"}) > 1000
for: 5m
annotations:
  summary: "Outbox event backlog detected"
  runbook: "Check OutboxEventPollerService logs"
```

---

## Testing

### Test: Atomicity Guarantee

```java
@Test
void outboxEventCreatedAtomicallyWithSubscription() {
    // Act: Create subscription
    Subscription subscription = subscriptionService.create(request);
    
    // Assert: Both changes visible
    Subscription saved = subscriptionRepository.findById(subscription.getId()).orElseThrow();
    assertThat(saved).isNotNull();
    
    List<OutboxEvent> events = outboxEventRepository.findByAggregateId(subscription.getId());
    assertThat(events)
        .hasSize(1)
        .first()
        .extracting("eventType", "status")
        .containsExactly("SubscriptionCreated", OutboxStatus.PENDING);
}
```

### Test: Duplicate Handling

```java
@Test
void eventHandlerIsIdempotent() {
    OutboxEvent event = createTestEvent("SubscriptionCreated");
    
    // Call handler twice
    handler.handleSubscriptionCreated(event.getPayload());
    handler.handleSubscriptionCreated(event.getPayload());  // Duplicate call
    
    // Verify email sent only once
    verify(emailService, times(1)).send(any());
}
```

---

## Related ADRs

- [ADR-001: Schema-Per-Tenant](./ADR-001-schema-per-tenant.md)
- [ADR-003: Razorpay 2-Step Payment](./ADR-003-razorpay-2step-payment.md)

---

## References

- "Transactional Outbox" by Chris Richardson: https://microservices.io/patterns/data/transactional-outbox.html
- Uber's Event Sourcing System: https://eng.uber.com/designing-schematized-kafka-topics/
- PostgreSQL JSONB: https://www.postgresql.org/docs/14/datatype-json.html
