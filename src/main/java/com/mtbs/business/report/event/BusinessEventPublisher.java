package com.mtbs.business.report.event;

import com.mtbs.shared.event.billing.BillingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes business billing events (customer invoices and payments).
 *
 * Used by: BusinessInvoiceService, BusinessPaymentService
 * Listener: NotificationService.handleBillingEvent(BillingEvent)
 *
 * Events routed through this publisher:
 *   BUSINESS_INVOICE_SENT, BUSINESS_PAYMENT_RECORDED
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(BillingEvent event) {
        try {
            eventPublisher.publishEvent(event);
            log.debug("Business event published: {}", event.getEventType());
        } catch (Exception e) {
            log.warn("Failed to publish business event {}: {}", event.getEventType(), e.getMessage());
        }
    }
}