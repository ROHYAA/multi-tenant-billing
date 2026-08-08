package com.mtbs.notification.listener;

import com.mtbs.notification.service.NotificationService;
import com.mtbs.shared.event.auth.AuthNotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationService notificationService;

    @EventListener
    public void handleBillingEvent(BillingEvent event) {
        log.info("Handling billing notification: type={}, tenant={}", event.getEventType(), event.getTenantId());
        notificationService.handleBillingEvent(event);
    }

    @EventListener
    public void handleAuthEvent(AuthNotificationEvent event) {
        log.info("Handling auth notification: type={}, recipient={}", event.getEventType(), event.getRecipientEmail());
        notificationService.handleAuthEvent(event);
    }
}