package com.mtbs.shared.event;

import java.time.Instant;

/**
 * Marker interface for all domain events in the system.
 *
 * All Spring application events (BillingEvent, AuthNotificationEvent,
 * BusinessEvent) should implement this interface so:
 *   1. Event listeners can type-check with a single base type
 *   2. The future OutboxEventPublisher can serialize any event uniformly
 *   3. Logging/tracing infrastructure can log event metadata without
 *      knowing the concrete type
 *
 * CONTRACT:
 *   - getEventId()   — unique identifier per event instance (UUID recommended)
 *   - getOccurredAt() — UTC timestamp when the event was created
 *   - getEventType() — discriminator string for serialization and routing
 *
 * EXISTING EVENTS that should implement this:
 *   BillingEvent, AuthNotificationEvent — add {@code implements DomainEvent}
 *   to both classes. The default methods provide backward-compatible defaults
 *   so no existing code breaks.
 */
public interface DomainEvent {

    /**
     * Unique identifier for this event instance.
     * Used for idempotency checking in the outbox processor.
     * Default: random UUID — override if you need deterministic IDs.
     */
    default String getEventId() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * UTC timestamp when this event was created.
     * Default: Instant.now() at call time.
     */
    default Instant getOccurredAt() {
        return Instant.now();
    }

    /**
     * Discriminator string used for routing, serialization, and outbox storage.
     * Should return the NotificationEvent name or a stable event type identifier.
     * Example: "USER_REGISTERED", "INVOICE_PAID", "BUSINESS_INVOICE_SENT"
     */
    String getEventType();
}