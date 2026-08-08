package com.mtbs.billing.event;

import com.mtbs.shared.event.billing.PaymentCapturedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCapturedEventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publish(PaymentCapturedEvent event) {
        try {
            eventPublisher.publishEvent(event);
            log.debug("Payment Captured event published: {}", event.getEventType());
        } catch (Exception e) {
            log.warn("Failed to publish payment event {}: {}", event.getEventType(), e.getMessage());
        }
    }
}
