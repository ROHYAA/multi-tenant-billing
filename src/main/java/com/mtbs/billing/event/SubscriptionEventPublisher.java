package com.mtbs.billing.event;

import com.mtbs.shared.event.billing.BillingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes subscription and onboarding lifecycle events.
 *
 * Used by: SubscriptionService, OnboardingCompletionService
 * Listener: NotificationService.handleBillingEvent(BillingEvent)
 *
 * Events routed through this publisher:
 *   TRIAL_STARTED, TRIAL_ENDING_SOON, TRIAL_EXPIRED,
 *   SUBSCRIPTION_ACTIVATED, SUBSCRIPTION_CANCELLED, SUBSCRIPTION_EXPIRED,
 *   SUBSCRIPTION_RENEWED, PLAN_UPGRADED, PLAN_DOWNGRADED,
 *   ONBOARDING_COMPLETED
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(BillingEvent event) {
        try {
            eventPublisher.publishEvent(event);
            log.debug("Subscription event published: {}", event.getEventType());
        } catch (Exception e) {
            log.warn("Failed to publish subscription event {}: {}", event.getEventType(), e.getMessage());
        }
    }
}