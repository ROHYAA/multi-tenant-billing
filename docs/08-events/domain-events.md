---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - domain-events
  - event-driven-architecture
  - outbox-pattern
  - event-publishing
  - application-events
  - transactional-events
  - event-handlers
related_documents:
  - ./notification-module.md
  - ./outbox-pattern.md
  - ./observability.md
  - ../ENTERPRISE_REFACTORING_SUMMARY.md
---

# Domain Events: Publishing, Handling, & Propagation

## Executive Summary

**Domain Events** are significant business occurrences published by services to notify other parts of the system (e.g., "Subscription Upgraded", "Payment Verified", "Invoice Generated"). MTBS uses event-driven architecture with **Spring ApplicationEvents** + **Outbox Pattern** for reliability.

This document covers:
1. **Event Design** — what constitutes a domain event, event types, structure
2. **Publishing Mechanism** — transactional event publishing (Outbox Pattern)
3. **Event Handlers** — who listens and how to implement handlers
4. **Event Ordering** — ensuring causal consistency (events process in order)
5. **Error Handling** — retry logic, dead letter queues
6. **Testing** — unit and integration tests for event flows

Key insight: Events are async, but must be **transactional** (all-or-nothing with DB changes).

---

## Context / Problem

### Historical Problem: Lost Events

**Scenario: Payment Captured**

```
1. PaymentService captures payment in DB:
   UPDATE invoices SET status = PAID
   
2. PaymentService publishes event:
   publishEvent(new PaymentCapturedEvent(...))
   
3. NotificationService listens, sends email
   → Database updated successfully
   → Event published successfully
   
4. MTBS crashes after payment DB update but before event publishing

Result:
   Payment marked as paid in DB
   But notification never sent (no email)
   Customer confused: "I paid but got no confirmation"
```

**Solution: Transactional Event Publishing (Outbox Pattern)**

```
1. PaymentService:
   BEGIN TRANSACTION
   UPDATE invoices SET status = PAID
   INSERT INTO outbox (event_type, payload) VALUES (...)
   COMMIT
   
2. OutboxProcessor (separate):
   SELECT * FROM outbox WHERE status = PENDING
   → Publishes events to ApplicationEventPublisher
   → DELETE FROM outbox (after successful publish)
   
3. NotificationService:
   @EventListener
   public void onPaymentCaptured(PaymentCapturedEvent event) {
       sendEmail(...);
   }

Guarantee: Either both payment + outbox write, or neither (atomic)
If crash between outbox write and event publish: outbox still has event, retry later
```

---

## Dependencies

### Inbound (What publishes events)
- **PaymentService** — publishes PaymentCapturedEvent, PaymentFailedEvent
- **SubscriptionService** — publishes PlanUpgradedEvent, DowngradedEvent, CreatedEvent
- **InvoiceService** — publishes InvoiceGeneratedEvent, InvoiceOverdueEvent
- **UserService** — publishes UserRegisteredEvent, UserDeactivatedEvent

### Outbound (Who consumes events)
- **NotificationService** — sends emails based on events
- **AnalyticsService** — tracks events for metrics
- **AuditService** — logs all events for compliance
- **SubscriptionScheduler** — triggered by events to update state

### Configuration
```yaml
spring:
  application:
    name: mtbs
  
  # ApplicationEvent propagation
  context:
    event:
      order: LOWEST_PRECEDENCE  # Process events after transaction commits

app:
  events:
    outbox:
      enabled: true
      poll-interval-seconds: 5
      batch-size: 100
      max-retries: 3
    
    async:
      enabled: true
      thread-pool-size: 10
```

---

## Design / Implementation

### Layer 1: Event Hierarchy

#### Base Event Class

```java
@Data
@AllArgsConstructor
public abstract class DomainEvent {
    private String eventId;  // Unique event ID for deduplication
    private String aggregateId;  // Entity that triggered event
    private String aggregateType;  // e.g., "Subscription"
    private LocalDateTime occurredAt;
    private String tenantId;
    private String userId;
    private String correlationId;  // For tracing
    
    protected DomainEvent(String aggregateId, String aggregateType, String tenantId, String correlationId) {
        this.eventId = UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.occurredAt = LocalDateTime.now();
        this.tenantId = tenantId;
        this.correlationId = correlationId;
    }
}
```

#### Specific Event Types

```java
// Payment Events
@Data
public class PaymentCapturedEvent extends DomainEvent {
    private String invoiceId;
    private String razorpayPaymentId;
    private BigDecimal amount;
    private String currency;
    private Instant capturedAt;
    
    public PaymentCapturedEvent(
        String invoiceId, String razorpayPaymentId, BigDecimal amount,
        String tenantId, String correlationId) {
        
        super(invoiceId, "Payment", tenantId, correlationId);
        this.invoiceId = invoiceId;
        this.razorpayPaymentId = razorpayPaymentId;
        this.amount = amount;
        this.currency = "INR";
        this.capturedAt = Instant.now();
    }
}

@Data
public class PaymentFailedEvent extends DomainEvent {
    private String invoiceId;
    private String razorpayOrderId;
    private String failureReason;  // SIGNATURE_INVALID, INSUFFICIENT_FUNDS
    private Instant failedAt;
    
    public PaymentFailedEvent(
        String invoiceId, String razorpayOrderId, String failureReason,
        String tenantId, String correlationId) {
        
        super(invoiceId, "Payment", tenantId, correlationId);
        this.invoiceId = invoiceId;
        this.razorpayOrderId = razorpayOrderId;
        this.failureReason = failureReason;
        this.failedAt = Instant.now();
    }
}

// Subscription Events
@Data
public class PlanUpgradedEvent extends DomainEvent {
    private String subscriptionId;
    private String fromPlanId;
    private String toPlanId;
    private BigDecimal chargeAmount;
    private Instant upgradedAt;
    
    public PlanUpgradedEvent(
        String subscriptionId, String fromPlanId, String toPlanId,
        BigDecimal chargeAmount, String tenantId, String correlationId) {
        
        super(subscriptionId, "Subscription", tenantId, correlationId);
        this.subscriptionId = subscriptionId;
        this.fromPlanId = fromPlanId;
        this.toPlanId = toPlanId;
        this.chargeAmount = chargeAmount;
        this.upgradedAt = Instant.now();
    }
}

@Data
public class SubscriptionCreatedEvent extends DomainEvent {
    private String subscriptionId;
    private String planId;
    private BillingCycle billingCycle;
    private Instant trialEndAt;
    
    public SubscriptionCreatedEvent(
        String subscriptionId, String planId, BillingCycle billingCycle,
        Instant trialEnd, String tenantId, String correlationId) {
        
        super(subscriptionId, "Subscription", tenantId, correlationId);
        this.subscriptionId = subscriptionId;
        this.planId = planId;
        this.billingCycle = billingCycle;
        this.trialEndAt = trialEnd;
    }
}

// Invoice Events
@Data
public class InvoiceGeneratedEvent extends DomainEvent {
    private String invoiceId;
    private String subscriptionId;
    private BigDecimal amount;
    private Instant dueDate;
    
    public InvoiceGeneratedEvent(
        String invoiceId, String subscriptionId, BigDecimal amount,
        Instant dueDate, String tenantId, String correlationId) {
        
        super(invoiceId, "Invoice", tenantId, correlationId);
        this.invoiceId = invoiceId;
        this.subscriptionId = subscriptionId;
        this.amount = amount;
        this.dueDate = dueDate;
    }
}
```

---

### Layer 2: Publishing with Outbox Pattern

#### Outbox Entity

```java
@Entity
@Table(name = "outbox_events")
@Data
public class OutboxEvent {
    @Id
    String id;
    
    @Column(nullable = false)
    String eventType;  // e.g., "PaymentCaptured", "PlanUpgraded"
    
    @Column(columnDefinition = "TEXT")
    String payload;  // JSON serialization of event
    
    @Column(nullable = false)
    OutboxStatus status;  // PENDING, PUBLISHED, FAILED
    
    @Column(nullable = false)
    Integer retryCount;
    
    String errorMessage;
    
    @Column(nullable = false)
    Instant createdAt;
    
    Instant publishedAt;
    
    Instant nextRetryAt;
}

enum OutboxStatus {
    PENDING,    // Waiting to be published
    PUBLISHED,  // Successfully published
    FAILED      // Max retries exceeded
}
```

#### Publishing Service

```java
@Service
@Slf4j
public class DomainEventPublisher {
    
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TenantContext tenantContext;
    
    /**
     * Publish domain event transactionally.
     * Event written to outbox table, NOT directly to ApplicationEventPublisher.
     * OutboxProcessor polls and publishes asynchronously.
     */
    @Transactional
    public void publish(DomainEvent event) {
        try {
            // Serialize event to JSON
            String payload = objectMapper.writeValueAsString(event);
            
            // Create outbox entry
            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setId(UUID.randomUUID().toString());
            outboxEvent.setEventType(event.getClass().getSimpleName());
            outboxEvent.setPayload(payload);
            outboxEvent.setStatus(OutboxStatus.PENDING);
            outboxEvent.setRetryCount(0);
            outboxEvent.setCreatedAt(Instant.now());
            outboxEvent.setNextRetryAt(Instant.now());
            
            // Write to outbox (same transaction as business logic)
            outboxRepository.save(outboxEvent);
            
            log.info("Event published to outbox: type={}, aggregateId={}, correlationId={}",
                event.getClass().getSimpleName(),
                event.getAggregateId(),
                event.getCorrelationId());
            
        } catch (Exception e) {
            log.error("Failed to publish event: {}", e.getMessage());
            throw new EventPublishingException("Cannot publish event", e);
        }
    }
}
```

#### Outbox Processor

```java
@Component
@Slf4j
public class OutboxEventProcessor {
    
    private final OutboxEventRepository outboxRepository;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    
    @Scheduled(fixedDelay = 5000)  // Poll every 5 seconds
    public void processPendingEvents() {
        try {
            // Find all pending outbox events
            List<OutboxEvent> pending = outboxRepository.findByStatus(OutboxStatus.PENDING);
            
            for (OutboxEvent outbox : pending) {
                try {
                    processEvent(outbox);
                } catch (Exception e) {
                    handleEventFailure(outbox, e);
                }
            }
        } catch (Exception e) {
            log.error("Error processing outbox events: {}", e.getMessage());
        }
    }
    
    private void processEvent(OutboxEvent outbox) {
        try {
            // Deserialize JSON back to domain event
            DomainEvent event = deserializeEvent(outbox);
            
            // Publish to ApplicationEventPublisher (async, triggers @EventListener)
            applicationContext.publishEvent(event);
            
            // Mark as published
            outbox.setStatus(OutboxStatus.PUBLISHED);
            outbox.setPublishedAt(Instant.now());
            outboxRepository.save(outbox);
            
            log.info("Outbox event published: type={}, aggregateId={}, correlationId={}",
                outbox.getEventType(),
                event.getAggregateId(),
                event.getCorrelationId());
            
        } catch (Exception e) {
            log.error("Error processing outbox event {}: {}", outbox.getId(), e.getMessage());
            throw e;
        }
    }
    
    private void handleEventFailure(OutboxEvent outbox, Exception e) {
        outbox.setRetryCount(outbox.getRetryCount() + 1);
        outbox.setErrorMessage(e.getMessage());
        
        if (outbox.getRetryCount() >= 3) {
            // Max retries exceeded
            outbox.setStatus(OutboxStatus.FAILED);
            log.error("Outbox event failed (max retries): type={}, error={}",
                outbox.getEventType(), e.getMessage());
            
            // Alert monitoring
            alerting.alertOutboxEventFailure(outbox);
        } else {
            // Retry in 30 seconds
            outbox.setNextRetryAt(Instant.now().plusSeconds(30));
        }
        
        outboxRepository.save(outbox);
    }
    
    private DomainEvent deserializeEvent(OutboxEvent outbox) throws JsonProcessingException {
        String eventType = outbox.getEventType();
        
        // Map event type string to class
        Class<?> eventClass = switch (eventType) {
            case "PaymentCapturedEvent" -> PaymentCapturedEvent.class;
            case "PaymentFailedEvent" -> PaymentFailedEvent.class;
            case "PlanUpgradedEvent" -> PlanUpgradedEvent.class;
            case "SubscriptionCreatedEvent" -> SubscriptionCreatedEvent.class;
            case "InvoiceGeneratedEvent" -> InvoiceGeneratedEvent.class;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
        
        return (DomainEvent) objectMapper.readValue(outbox.getPayload(), eventClass);
    }
}
```

---

### Layer 3: Event Handlers

#### Synchronous Handler (Immediate)

```java
@Component
@Slf4j
public class PaymentCapturedEventHandler {
    
    private final SubscriptionService subscriptionService;
    
    @EventListener
    @Transactional
    public void on(PaymentCapturedEvent event) {
        log.info("Handling PaymentCapturedEvent: invoiceId={}, correlationId={}",
            event.getInvoiceId(), event.getCorrelationId());
        
        // MDC for logging
        MDC.put("correlationId", event.getCorrelationId());
        MDC.put("tenantId", event.getTenantId());
        
        try {
            // Activate subscription upgrade
            subscriptionService.activateUpgradeAfterPayment(event.getInvoiceId());
            
            log.info("Subscription upgrade activated successfully");
        } finally {
            MDC.clear();
        }
    }
}
```

#### Asynchronous Handler (Background)

```java
@Component
@Slf4j
public class NotificationEventHandler {
    
    private final NotificationService notificationService;
    private final AsyncConfig asyncConfig;
    
    @EventListener
    @Async("taskExecutor")  // Run in background thread
    public void on(PaymentCapturedEvent event) {
        log.info("Sending payment confirmation email");
        
        try {
            notificationService.sendPaymentConfirmationEmail(
                event.getTenantId(),
                event.getAggregateId(),
                event.getAmount()
            );
        } catch (Exception e) {
            log.error("Failed to send notification: {}", e.getMessage());
            // Don't rethrow: async handlers shouldn't fail the main flow
        }
    }
}
```

---

### Layer 4: Event Ordering

#### Problem: Out-of-Order Events

```
Event 1: SubscriptionCreatedEvent
Event 2: PlanUpgradedEvent  (subscription upgraded after 1 hour)

But due to async processing:
  Event 2 processed first (by chance, faster handler)
  → Try to upgrade subscription that doesn't exist yet
  → Error: "Subscription not found"
  
Event 1 processes after
  → Creates subscription (too late)
```

#### Solution: Ordered Processing Per Aggregate

```java
@Component
@Slf4j
public class OrderedEventProcessor {
    
    private final ConcurrentHashMap<String, LinkedBlockingQueue<DomainEvent>> queues 
        = new ConcurrentHashMap<>();
    
    private final ThreadPoolExecutor executor;
    
    @EventListener
    public void processEvent(DomainEvent event) {
        String aggregateId = event.getAggregateId();
        
        // Get or create queue for this aggregate
        LinkedBlockingQueue<DomainEvent> queue = queues.computeIfAbsent(
            aggregateId,
            k -> new LinkedBlockingQueue<>());
        
        queue.add(event);
        
        // Process queue asynchronously
        executor.execute(() -> {
            while (!queue.isEmpty()) {
                DomainEvent e = queue.poll();
                if (e != null) {
                    handleEventInOrder(e);
                }
            }
        });
    }
    
    private void handleEventInOrder(DomainEvent event) {
        log.info("Processing event in order: type={}, aggregateId={}",
            event.getClass().getSimpleName(),
            event.getAggregateId());
        
        // Dispatch to appropriate handler
        if (event instanceof SubscriptionCreatedEvent e) {
            subscriptionHandler.handle(e);
        } else if (event instanceof PlanUpgradedEvent e) {
            subscriptionHandler.handle(e);
        }
    }
}
```

**Result:** All events for same subscription (aggregateId) processed in order.

---

## Testing Events

### Unit Test: Event Publishing

```java
@Test
public void testPaymentCapturedEventPublished() {
    // Arrange
    String invoiceId = "inv-123";
    String tenantId = "t1";
    
    // Act
    paymentService.capturePayment(invoiceId);
    
    // Assert: Check outbox contains event
    OutboxEvent outbox = outboxEventRepository.findByAggregateId(invoiceId);
    assertThat(outbox).isNotNull();
    assertThat(outbox.getEventType()).isEqualTo("PaymentCapturedEvent");
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
}
```

### Integration Test: Event Handling

```java
@SpringBootTest
@Transactional
public class EventHandlingIntegrationTest {
    
    @Autowired
    private DomainEventPublisher eventPublisher;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private SubscriptionService subscriptionService;
    
    @Test
    public void testPaymentCapturedTriggersSubscriptionUpgrade() throws Exception {
        // Arrange
        String subscriptionId = "s-123";
        String invoiceId = "inv-999";
        
        // Publish event manually (in real scenario, would come from payment service)
        PaymentCapturedEvent event = new PaymentCapturedEvent(
            invoiceId, "pay_xyz", BigDecimal.valueOf(299.99),
            "t1", UUID.randomUUID().toString()
        );
        
        // Act: Publish and wait for handlers
        applicationContext.publishEvent(event);
        Thread.sleep(1000);  // Wait for async processing
        
        // Assert: Subscription upgraded
        Subscription upgraded = subscriptionService.getSubscription(subscriptionId);
        assertThat(upgraded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }
}
```

---

## Known Issues / Limitations

1. **Transactional Outbox Doesn't Guarantee Exactly-Once** — Outbox ensures at-least-once (event sent multiple times if retry). Application must be idempotent.

2. **Event Handler Exceptions** — If @EventListener throws exception, other listeners may not run. Solution: Catch and handle exceptions in handlers.

3. **Async Event Ordering** — Multiple instances may process events concurrently (out of order). Solution: Use OrderedEventProcessor or enforce ordering explicitly.

4. **Outbox Polling Latency** — Events published in DB, but not immediately to handlers. Default 5s poll interval = up to 5s delay. Solution: Reduce poll interval or use database triggers (advanced).

5. **Outbox Table Growth** — Published events accumulate. Solution: Archive old events, implement retention policy.

---

## Future Improvements

1. **Event Sourcing** — Store all domain events as single source of truth. Reconstruct entity state by replaying events.

2. **CQRS (Command Query Responsibility Segregation)** — Separate write model (commands, events) from read model (projections). Denormalize data in read tables for fast queries.

3. **Saga Pattern** — Orchestrate long-running transactions (e.g., multi-step payment flow) with saga coordinators.

4. **Dead Letter Queue** — Route permanently failed events to DLQ for investigation.

5. **Event Versioning** — Support schema evolution: old events with old schema, new events with new schema, migration layer handles both.

---

## Related Documents

- [notification-events.md](./notification-events.md) — Specific notification event types
- [notification-module.md](./notification-module.md) — How events trigger notifications
- [outbox-pattern.md](../arch/OUTBOX_PATTERN_GUIDE.md) — Detailed Outbox Pattern explanation
- [observability.md](./observability.md) — Event logging and tracing
