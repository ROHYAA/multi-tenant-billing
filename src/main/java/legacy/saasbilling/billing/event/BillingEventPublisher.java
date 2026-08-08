package legacy.saasbilling.billing.event;

import com.mtbs.shared.event.bill.BillEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes platform billing events (invoices, payments, usage).
 *
 * Used by: PaymentService, InvoiceService, UsageService
 * Listener: NotificationService.handleBillingEvent(BillEvent)
 *
 * Events routed through this publisher:
 *   INVOICE_GENERATED, INVOICE_PAID, INVOICE_OVERDUE,
 *   PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_RETRY, PAYMENT_REFUNDED,
 *   USAGE_LIMIT_WARNING, USAGE_LIMIT_REACHED
 *
 * NOTE: publishAuthEvent() has been removed.
 *   Auth events → AuthEventPublisher
 *   Subscription events → SubscriptionEventPublisher
 *   Business billing events → BusinessEventPublisher
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(BillEvent event) {
        try {
            eventPublisher.publishEvent(event);
            log.debug("Billing event published: {}", event.getEventType());
        } catch (Exception e) {
            log.warn("Failed to publish billing event {}: {}", event.getEventType(), e.getMessage());
        }
    }
}