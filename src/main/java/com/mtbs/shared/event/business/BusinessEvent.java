package com.mtbs.shared.event.business;

import com.mtbs.shared.event.DomainEvent;
import com.mtbs.shared.enums.notification.NotificationEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event payload for business billing domain events.
 *
 * Published by: BusinessEventPublisher (in business/report/event/)
 * Consumed by:  NotificationService.handleBillingEvent(BillingEvent)
 *               — BusinessEvent maps to BillingEvent in the listener for now.
 *               When the outbox pattern is added, this becomes the canonical
 *               event for business domain operations.
 *
 * Events routed through this type:
 *   BUSINESS_INVOICE_SENT, BUSINESS_PAYMENT_RECORDED
 *
 * NOTE: Currently BusinessEventPublisher publishes BillingEvent (not this class)
 * because NotificationService listens on BillingEvent. When the outbox processor
 * is wired in Phase 2, publishers will switch to strongly-typed domain events
 * and the dispatcher will map them to notification payloads.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessEvent implements DomainEvent {

    @Builder.Default
    private String eventId = UUID.randomUUID().toString();

    @Builder.Default
    private Instant occurredAt = Instant.now();

    private NotificationEvent notificationEvent;

    // Tenant context
    private Long tenantId;
    private String tenantName;

    // Recipient (customer)
    private String recipientEmail;
    private String recipientName;

    // Invoice context
    private String invoiceNumber;
    private BigDecimal invoiceTotal;
    private String currency;
    private Instant dueDate;

    // Payment context
    private BigDecimal amountPaid;
    private BigDecimal outstandingAmount;
    private Instant paidAt;

    // PDF attachment (bytes — not serialized to outbox, generated fresh on delivery)
    private transient byte[] pdfAttachment;

    // Payment link URL (Razorpay plink)
    private String paymentLinkUrl;

    // Dynamic extra data for template variables not covered by typed fields
    private Map<String, Object> extra;

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String getEventType() {
        return notificationEvent != null ? notificationEvent.name() : "BUSINESS_EVENT";
    }
}