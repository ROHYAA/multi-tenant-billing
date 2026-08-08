package com.mtbs.billing.scheduler.job;

import com.mtbs.billing.dto.InvoiceResponse;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.repository.PlanRepository;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.billing.service.SubscriptionService;
import com.mtbs.billing.service.InvoiceService;
import com.mtbs.billing.service.PaymentService;
import com.mtbs.shared.enums.auth.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs daily at 08:00 UTC.
 *
 * Finds all TRIALING subscriptions whose trial ends in the next
 * 3-day window and:
 * 1. Fires a TRIAL_ENDING_SOON notification
 * 2. Generates an invoice for the subscription plan
 * 3. Creates a Razorpay payment link
 * 4. Sends email with invoice + payment link
 *
 * Each tenant is processed in isolation — TenantContext is set and cleared
 * per tenant so schema routing works correctly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrialEndingSoonJob implements Job {

    private static final int DAYS_BEFORE_EXPIRY = 3;

    private final TenantService tenantService;
    private final SubscriptionService subscriptionService;
    private final PlanRepository planRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("TrialEndingSoonJob started at {}", Instant.now());

        List<Tenant> activeTenants = tenantService.getTenantsByStatusList(Status.ACTIVE);
        Instant now = Instant.now();
        Instant windowEnd = now.plus(DAYS_BEFORE_EXPIRY, ChronoUnit.DAYS);

        int notified = 0;
        AtomicInteger invoicesGenerated = new AtomicInteger();

        for (Tenant tenant : activeTenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());

                subscriptionService
                        .findFirstSubscriptionByStatuses(List.of(SubscriptionStatus.TRIALING))
                        .ifPresent(sub -> {
                            if (isTrialEndingWithinWindow(sub, now, windowEnd)) {
                                try {
                                    long daysLeft = ChronoUnit.DAYS.between(now, sub.getTrialEnd());
                                    
                                    fireNotification(tenant, sub, daysLeft);
                                    generateInvoiceAndPaymentLink(tenant, sub);
                                    invoicesGenerated.getAndIncrement();
                                    
                                } catch (Exception e) {
                                    log.error("Failed to process trial ending for tenant {}: {}", 
                                            tenant.getId(), e.getMessage(), e);
                                }
                            }
                        });

                notified++;
            } catch (Exception e) {
                log.error("TrialEndingSoonJob failed for tenantId={}: {}", 
                        tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }

        log.info("TrialEndingSoonJob completed — processed {} tenants, generated {} invoices", 
                notified, invoicesGenerated);
    }

    private boolean isTrialEndingWithinWindow(Subscription sub, Instant now, Instant windowEnd) {
        Instant trialEnd = sub.getTrialEnd();
        return trialEnd != null
                && trialEnd.isAfter(now)
                && !trialEnd.isAfter(windowEnd);
    }

    private void fireNotification(Tenant tenant, Subscription sub, long daysLeft) {
        try {
            String planDisplayName = planRepository.findById(sub.getPlanId())
                    .map(Plan::getDisplayName)
                    .orElse("your plan");

            outboxEventPublisher.save(BillingEvent.builder()
                    .eventType(NotificationEvent.TRIAL_ENDING_SOON)
                    .tenantId(tenant.getId())
                    .tenantName(tenant.getName())
                    .recipientEmail(tenant.getOwnerEmail())
                    .recipientName(tenant.getName())
                    .planName(planDisplayName)
                    .trialEndsAt(sub.getTrialEnd())
                    .build(), "Subscription", sub.getId());

            log.info("TRIAL_ENDING_SOON fired — tenantId={}, daysLeft={}, trialEnd={}",
                    tenant.getId(), daysLeft, sub.getTrialEnd());

        } catch (Exception e) {
            log.warn("Failed to fire TRIAL_ENDING_SOON for tenantId={}: {}",
                    tenant.getId(), e.getMessage());
        }
    }

    private void generateInvoiceAndPaymentLink(Tenant tenant, Subscription sub) {
        try {
            Instant periodStart = sub.getCurrentPeriodStart() != null 
                    ? sub.getCurrentPeriodStart() 
                    : sub.getTrialStart();
            Instant periodEnd = sub.getCurrentPeriodEnd() != null 
                    ? sub.getCurrentPeriodEnd() 
                    : sub.getTrialEnd();

            InvoiceResponse invoice = invoiceService.generateInvoice(
                    sub.getId(), 
                    periodStart, 
                    periodEnd
            );

            invoiceService.finalizeInvoice(invoice.getId());

            var orderResponse = paymentService.processPayment(invoice.getId());

            log.info("Invoice generated for trial ending soon — tenantId={}, invoiceId={}, orderId={}",
                    tenant.getId(), invoice.getId(), orderResponse.getOrderId());

        } catch (Exception e) {
            log.error("Failed to generate invoice for tenantId={}: {}", 
                    tenant.getId(), e.getMessage(), e);
        }
    }
}
