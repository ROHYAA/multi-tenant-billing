package com.mtbs.admin.service;

import com.mtbs.auth.service.SchemaCacheService;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.auth.AuthNotificationEvent;
import com.mtbs.tenant.entity.Shop;
import com.mtbs.tenant.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Offline-payment subscription tracking: once a day, alerts shops entering
 * their 5-day pre-expiry window and auto-suspends anything that's actually
 * lapsed. Deliberately publishes AuthNotificationEvent directly via
 * ApplicationEventPublisher rather than the tenant-schema outbox
 * (OutboxEventPublisher) — this job iterates the public-schema shops table
 * across every tenant with no single active TenantContext, and outbox_events
 * only exists per-tenant-schema (the same reason ShopService.approveTenant's
 * Javadoc gives for not self-firing an audit event from admin context).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionExpiryJob {

    private static final Duration ALERT_WINDOW = Duration.ofDays(5);

    private final ShopRepository shopRepository;
    private final SchemaCacheService schemaCacheService;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "${app.subscription-expiry.cron:0 0 6 * * *}")
    @Transactional
    public void run() {
        Instant now = Instant.now();
        alertExpiringSoon(now);
        suspendExpired(now);
    }

    private void alertExpiringSoon(Instant now) {
        List<Shop> expiringSoon = shopRepository
                .findByStatusAndSubscriptionExpiresAtBetweenAndExpiryAlertSentAtIsNull(
                        Status.ACTIVE, now, now.plus(ALERT_WINDOW));

        for (Shop shop : expiringSoon) {
            log.info("Subscription expiring soon: tenantId={} plan={} expiresAt={}",
                    shop.getId(), shop.getPlanName(), shop.getSubscriptionExpiresAt());

            publish(NotificationEvent.SUBSCRIPTION_EXPIRING_SOON, shop);

            shop.setExpiryAlertSentAt(now);
            shopRepository.save(shop);
        }
    }

    private void suspendExpired(Instant now) {
        List<Shop> expired = shopRepository.findByStatusAndSubscriptionExpiresAtBefore(Status.ACTIVE, now);

        for (Shop shop : expired) {
            log.info("Subscription expired — auto-suspending: tenantId={} plan={} expiresAt={}",
                    shop.getId(), shop.getPlanName(), shop.getSubscriptionExpiresAt());

            shop.setStatus(Status.SUSPENDED);
            shopRepository.save(shop);
            schemaCacheService.evict(shop.getId());

            publish(NotificationEvent.SUBSCRIPTION_EXPIRED, shop);
        }
    }

    private void publish(NotificationEvent eventType, Shop shop) {
        if (shop.getOwnerEmail() == null) {
            log.warn("Skipping {} notification for tenantId={} — no owner email on file", eventType, shop.getId());
            return;
        }
        eventPublisher.publishEvent(AuthNotificationEvent.builder()
                .eventType(eventType)
                .recipientEmail(shop.getOwnerEmail())
                .recipientName(shop.getName())
                .tenantName(shop.getName())
                .eventTime(Instant.now())
                .planName(shop.getPlanName())
                .subscriptionExpiresAt(shop.getSubscriptionExpiresAt())
                .build());
    }
}
