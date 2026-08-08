---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - events
  - listeners
  - notifications
  - audit
  - billing
  - domain-driven-design
related_documents:
  - ./outbox-pattern.md
  - ./system-design.md
---

# Event Flow

## Executive Summary

MTBS uses **domain-driven event publishing** to decouple billing, notification, and audit concerns. When a tenant triggers an action (invoice created, payment captured, user registered), a `DomainEvent` is published synchronously to Spring's `ApplicationEventPublisher`. Event listeners (`@EventListener` annotated methods) subscribe to specific event types and execute side effects (send emails, log audit trails, track metrics). Events are persisted via the transactional outbox pattern (see outbox-pattern.md) for reliability. This document maps all event types, publishers, and listeners.

---

## Context / Problem

### Why Domain Events?

Without events, a service creates an invoice and must directly call the notification service:

```java
// WRONG: Tight coupling
@Service
public class InvoiceService {
    @Autowired private NotificationService notif;
    
    public void createInvoice(InvoiceRequest req) {
        Invoice inv = invoiceRepository.save(...);
        notif.sendInvoiceCreatedNotification(inv);  // ← Hard dependency
        // If notif fails, entire transaction fails
    }
}
```

Problems:
- Tight coupling: InvoiceService knows about NotificationService
- Synchronous: If notification is slow, invoice creation is slow
- Fragile: New feature (audit logging) requires modifying InvoiceService
- Hard to test: Mocking NotificationService pollutes tests

### With Domain Events:

```java
// RIGHT: Event-driven, loosely coupled
@Service
public class InvoiceService {
    @Autowired private OutboxEventPublisher outbox;
    
    public void createInvoice(InvoiceRequest req) {
        Invoice inv = invoiceRepository.save(...);
        outbox.save(new InvoiceCreatedEvent(inv));  // ← Fire event only
        // No knowledge of listeners; decoupled
    }
}

@Component
public class NotificationListener {
    @EventListener
    public void onInvoiceCreated(InvoiceCreatedEvent e) {
        sendInvoiceCreatedNotification(e);
    }
}

@Component
public class AuditListener {
    @EventListener
    public void onAnyEvent(DomainEvent e) {
        auditLog.log("Invoice created: " + e.getAggregateId());
    }
}
```

Benefits:
- No coupling: InvoiceService doesn't know NotificationListener exists
- Pluggable: Add AuditListener without touching InvoiceService
- Testable: Test InvoiceService without mocking listeners
- Reliable: Outbox ensures events are persisted even if listeners fail

---

## Dependencies

### Inbound (Who Publishes Events)
- `InvoiceService` → `BillingEventPublisher.publish()` — On invoice creation/payment
- `SubscriptionService` → `SubscriptionEventPublisher.publish()` — On plan changes
- `PaymentService` → `PaymentCapturedEventPublisher.publish()` — On payment success
- `AuthService` → `AuthEventPublisher.publish()` — On user registration/login
- `AuditService` → `AuditLogService.publishEvent()` — On data changes

### Outbound (Who Listens to Events)
- `NotificationService` → `@EventListener` — Send emails on invoice/payment events
- `AuditService` → `@EventListener` — Log all events to audit table
- `MetricsService` → `@EventListener` — Track billing metrics
- `UsageTrackingService` → `@EventListener` — Count API calls
- Custom listeners (defined by deployments)

### Configuration
- `spring.application.listeners.async-support.enabled: false` — Events are synchronous by default (no threading)
- Event listeners can be made async via `@Async` if needed

---

## Design / Implementation

### Event Hierarchy

```
DomainEvent (interface)
├── BillingEvent
│   └─ eventType: INVOICE_GENERATED, INVOICE_PAID, INVOICE_OVERDUE, 
│                  PAYMENT_SUCCEEDED, PAYMENT_FAILED, USAGE_LIMIT_WARNING
├── SubscriptionEvent
│   └─ eventType: SUBSCRIPTION_CREATED, PLAN_UPGRADED, PLAN_DOWNGRADED, SUBSCRIPTION_CANCELLED
├── AuthNotificationEvent
│   └─ eventType: USER_REGISTERED, PASSWORD_RESET_REQUESTED, EMAIL_VERIFICATION_SENT
├── AuthEvent (internal)
│   └─ eventType: LOGIN_SUCCESS, LOGIN_FAILED, LOGOUT_SUCCESS
├── BusinessEvent (tenant's business domain)
│   └─ eventType: CUSTOMER_CREATED, INVOICE_SENT, PAYMENT_RECEIVED
├── AuditLogEvent
│   └─ eventType: (any event type for audit trail)
└── [Custom events as needed]
```

### Event Publishing Points

#### BillingEvent Flow

```
InvoiceService.createInvoice()
    ↓
1. INSERT INTO invoices (amount, status=DRAFT)
2. BillingEventPublisher.publish(
       InvoiceGeneratedEvent(invoiceId=100, amount=999.00)
   )
3. OutboxEventPublisher.save(InvoiceGeneratedEvent)  ← Stored in outbox
4. COMMIT
    ↓
ApplicationEventPublisher.publishEvent()  ← Dispatches to listeners
    ├─ NotificationListener.onInvoiceGenerated()
    │  └─ Send email: "Invoice #100 for $999.00"
    ├─ AuditListener.onAnyEvent()
    │  └─ Log: "INVOICE_GENERATED: invoice_id=100"
    └─ MetricsListener.onBillingEvent()
       └─ Increment: "invoices.created" counter
```

#### PaymentService.capturePayment()

```
1. Verify Razorpay signature
2. UPDATE invoices SET status=PAID
3. PaymentCapturedEventPublisher.save(
       PaymentCapturedEvent(invoiceId=100, paymentId=456)
   )
4. COMMIT
    ↓
ApplicationEventPublisher.publishEvent()
    ├─ NotificationListener.onPaymentCaptured()
    │  └─ Send "Payment received" email
    ├─ AuditListener.onAnyEvent()
    │  └─ Log event
    ├─ UsageTrackingListener.onPaymentCaptured()
    │  └─ Update tenant billing status
    └─ AnalyticsListener.onPaymentCaptured()
       └─ Record revenue metric
```

#### SubscriptionService.upgradePlan()

```
1. Verify current plan + new plan compatibility
2. Create OPEN invoice for the upgrade cost
3. SubscriptionEventPublisher.save(
       PlanUpgradeInitiatedEvent(subscriptionId=789, oldPlan=FREE, newPlan=PRO)
   )
4. COMMIT
    ↓
ApplicationEventPublisher.publishEvent()
    ├─ NotificationListener.onPlanUpgrade()
    │  └─ Send "Plan upgrade initiated" email
    ├─ AuditListener.onAnyEvent()
    └─ BillingDashboardListener.onPlanUpgrade()
       └─ Refresh cached dashboard stats
```

#### AuthService.registerUser()

```
1. Hash password
2. INSERT INTO users (email, password_hash)
3. AuthEventPublisher.save(
       UserRegisteredEvent(userId=123, email="user@acme.com")
   )
4. COMMIT
    ↓
ApplicationEventPublisher.publishEvent()
    ├─ NotificationListener.onUserRegistered()
    │  └─ Send welcome email
    ├─ AuditListener.onAnyEvent()
    ├─ UsageTrackingListener.onUserRegistered()
    │  └─ Record new user for analytics
    └─ PermissionInitializerListener.onUserRegistered()
       └─ Grant default permissions
```

### Listener Implementation

**Base listener pattern:**

```java
@Component
@Slf4j
public class NotificationListener {
    
    @Autowired private EmailService emailService;
    
    @EventListener
    public void onInvoiceGenerated(InvoiceGeneratedEvent event) {
        try {
            log.info("Invoice generated: invoiceId={}", event.getInvoiceId());
            
            // Fetch full invoice details if needed
            // (event might only contain ID for performance)
            
            emailService.sendInvoiceEmail(
                event.getRecipientEmail(),
                event.getInvoiceNumber(),
                event.getAmount()
            );
            
        } catch (Exception ex) {
            log.error("Failed to send invoice email", ex);
            // Exception handling: retry via outbox mechanism
            // OR fail silently (may lose notification)
        }
    }
    
    @EventListener
    public void onPaymentCaptured(PaymentCapturedEvent event) {
        // Payment succeeded notification
        emailService.sendPaymentConfirmation(event);
    }
    
    @EventListener
    public void onSubscriptionExpiring(SubscriptionExpiryEvent event) {
        // Proactive: remind tenant their subscription expires soon
        emailService.sendExpiryWarning(event.getTenantEmail(), event.getExpiryDate());
    }
}

@Component
@Slf4j
public class AuditListener {
    
    @Autowired private AuditLogService auditLogService;
    
    @EventListener
    public void onAnyEvent(DomainEvent event) {
        // Log ALL domain events for compliance
        auditLogService.log(AuditLogEvent.builder()
            .eventType(event.getEventType())
            .tenantId(getCurrentTenantId())  // From TenantContext
            .userId(getCurrentUserId())  // From SecurityContext
            .timestamp(event.getOccurredAt())
            .metadata(objectMapper.valueToTree(event))
            .build()
        );
    }
}

@Component
@Slf4j
public class UsageTrackingListener {
    
    @Autowired private UsageSummaryService usageService;
    
    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        // Track metrics for business intelligence
        usageService.incrementMetric("users.registered", 1, event.getTenantId());
    }
    
    @EventListener
    public void onInvoiceGenerated(InvoiceGeneratedEvent event) {
        usageService.incrementMetric("invoices.generated", event.getAmount(), event.getTenantId());
    }
    
    @EventListener
    public void onPaymentCaptured(PaymentCapturedEvent event) {
        usageService.incrementMetric("revenue", event.getAmount(), event.getTenantId());
    }
}
```

### event Payload Structure

Events are serialized to JSON (for outbox storage):

```json
{
  "eventType": "INVOICE_GENERATED",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "occurredAt": "2026-04-24T10:15:30.123Z",
  "tenantId": 456,
  "invoiceId": 100,
  "invoiceNumber": "INV-2026-00100",
  "amount": 999.00,
  "currency": "USD",
  "status": "DRAFT",
  "dueDate": "2026-05-24",
  "recipientEmail": "owner@acme.com",
  "recipientName": "John Doe"
}
```

### Event Ordering & Idempotency

Events are published **synchronously** (in order), but listeners run **independently**:

```
Request Thread:
InvoiceService.create()
  ├─ Publish InvoiceGeneratedEvent
  ├─ Save to outbox (oubox INSERT)
  ├─ COMMIT
  └─ Return to client

Listener threads (async if @Async):
NotificationListener → Send Email
AuditListener → Log audit
UsageListener → Update metrics
[All can run in parallel; no guaranteed order]
```

**If ordering is critical** (e.g., UserRegistered must be processed before PermissionGranted):
1. Use @Order annotation on listeners
2. Make listeners sync (no @Async)
3. OR: Chain events (PermissionGranted event only published after UserRegistered is fully processed)

**Idempotency handling:**

```java
@Component
public class NotificationListener {
    
    @EventListener
    public void onInvoiceGenerated(InvoiceGeneratedEvent event) {
        // Check if email already sent (idempotency)
        if (sentEmailRepository.exists(
            tenantId=event.getTenantId(),
            invoiceId=event.getInvoiceId(),
            emailType="INVOICE_GENERATED"
        )) {
            log.debug("Email already sent; skipping duplicate");
            return;
        }
        
        // Send email + record that we sent it
        emailService.send(...);
        sentEmailRepository.save(SentEmail.builder()
            .tenantId(event.getTenantId())
            .invoiceId(event.getInvoiceId())
            .emailType("INVOICE_GENERATED")
            .sentAt(Instant.now())
            .build()
        );
    }
}
```

---

## Flow

See Event Publishing Points ASCII art above.

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `BillingEventPublisher` | [BIL-32] | `publish(BillingEvent)` | Publish billing events (sync) |
| `SubscriptionEventPublisher` | [BIL-33] | `publish(SubscriptionEvent)` | Publish subscription events |
| `PaymentCapturedEventPublisher` | [BIL-34] | `publish(PaymentCapturedEvent)` | Publish payment capture events |
| `AuthEventPublisher` | [AUTH-34] | `publish(AuthEvent)` | Publish auth events (login, logout) |
| `OutboxEventPublisher` | [BIL-35] | `save(event, aggregateType, aggregateId)` | Save event to outbox (persistence) |
| `DomainEvent` (interface) | [SHARED-X] | `getEventType()` | Event discriminator for routing/serialization |
| Listener methods | varies | `@EventListener void on.*()` | Subscribe to specific event types |

---

## Rules / Constraints

1. **All domain events MUST implement DomainEvent interface** — Allows uniform serialization, routing, and outbox storage. No exceptions.

2. **Event listeners MUST be idempotent** — If an event is published twice, listeners must produce the same outcome. Check if action already taken before re-executing.

3. **Event listeners MUST NOT throw uncaught exceptions** — If a listener fails, catch the exception and log (don't propagate). Otherwise entire event dispatch fails and subsequent listeners don't run.

4. **Event listeners MUST NOT modify listener order by accident** — Use explicit `@Order` annotations if order matters. Relying on alphabetical order is fragile.

5. **Events MUST include sufficient data for listeners to act** — Don't publish EventID-only; include tenant_id, amount, email address, etc. Listeners should not need to re-query the database.

6. **Events MUST NOT reference other events** — Events are immutable snapshots. No "triggered_by_event_id" backlinks; events are independent.

7. **Async listeners MUST propagate TenantContext manually** — ThreadLocal doesn't cross thread boundaries. Pass tenantId in event; listener sets TenantContext if needed.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| Listener throws exception | Any exception | Caught; 500 if critical event | Listener should catch; log error; not propagate |
| Email service down (listener calls it) | `MailException` | 500 | Email listener catches; retries via outbox; operator alerted |
| Too many listeners (slow dispatch) | (No exception; just slow) | Request takes long time | Offload slow listeners to async (@Async) or separate queue |
| Event payload serialization fails | `JsonProcessingException` | 500 | Check event fields are serializable; fix before deployment |
| Listener accesses wrong TenantContext | Silent bug (listener runs in wrong tenant) | N/A; data updated for wrong tenant | Always check event.getTenantId() matches TenantContext; test |
| Outbox event not published by scheduler | (No exception; daemon silently fails) | N/A; events accumulate | Check scheduler is running; logs show "OutboxEventProcessor" entries |

---

## Edge Cases

- **Concurrency**: Multiple endpoints publish events simultaneously. Spring event publisher is thread-safe; listeners run sequentially unless marked @Async. No race conditions.

- **Timezone**: Event timestamps are in UTC (Instant). Listeners can convert to local timezone for user display.

- **Tenant Isolation**: Events include tenantId; listeners MUST check event.getTenantId() matches TenantContext if they access data. No automatic isolation.

- **Async listeners**: If a listener is marked @Async, it runs in a thread pool thread. TenantContext is NOT inherited (ThreadLocal). Listener must set context manually from event.getTenantId().

- **Exception during event publishing** (e.g., one listener crashes): Spring event publisher catches exceptions from individual listeners and continues to next listener. Other listeners still run.

- **Listener registration order**: Spring discovers listeners via classpath scanning. Order is arbitrary unless explicitly set with @Order. If order matters, set @Order(1), @Order(2), etc.

---

## Known Issues / Limitations

1. **Event listeners are synchronous by default** — If a listener is slow (e.g., sends email), the entire event dispatch blocks. Request latency increases. Workaround: Mark listener @Async to run in thread pool.

2. **No event versioning** — If event structure changes (field added/removed), old serialized events can't deserialize. Workaround: Add version field to events; deserializers handle multiple versions.

3. **No event replay** — Events are not replayed to new listeners. If you add a new listener, it won't process historical events. Design: Save events to a separate event store if replay is needed.

4. **No guaranteed event ordering across services** — Events within a service are ordered (by DB transaction order), but across services (microservices), ordering is not guaranteed.

5. **Out-of-process listeners**: If a listener makes an HTTP call to another service, network failures aren't retried automatically. Listener must implement retry logic or use outbox for that call.

6. **No dead-letter queue (DLQ) for failed listeners** — If a listener fails after max retries, no special handling (no DLQ). Manual intervention needed. Recommendation: Use outbox DLQ for critical events.

---

## Future Improvements

1. Implement `@AsyncListener` wrapper — Automatically propagate TenantContext to async listeners. Reduces boilerplate.

2. Add event versioning + schema registry — Central registry of all event versions; deserializers handle multiple schema versions.

3. Implement event replay API — Admin endpoint to replay events from archive. Allows onboarding new listeners to historical events.

4. Add metrics publisher — Expose event publish/listen latencies as Prometheus metrics. Monitor listener performance.

5. Implement event dead-letter queue (DLQ) — Failed listeners move events to DLQ; separate processor handles DLQ with longer retry intervals.

6. Add event tracing — Export event flow to distributed tracing (Jaeger/Tempo). Visualize which listeners processed which events.

---

## Related Documents
- [outbox-pattern.md](./outbox-pattern.md) — Transactional outbox for reliable event persistence
- [request-flow.md](./request-flow.md) — HTTP request flow; where events are published
- [notification-events.md](../08-events/notification-events.md) — Notification-specific events + listeners
- [domain-events.md](../08-events/domain-events.md) — Catalog of all business domain events
- [audit-events.md](../08-events/audit-events.md) — Audit log events + compliance
- [system-design.md](./system-design.md) — Architecture overview
