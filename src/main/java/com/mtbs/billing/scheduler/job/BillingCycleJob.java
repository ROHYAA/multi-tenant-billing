package com.mtbs.billing.scheduler.job;

import com.mtbs.billing.entity.Subscription;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.billing.service.SubscriptionService;
import com.mtbs.billing.service.InvoiceService;
import com.mtbs.billing.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BillingCycleJob implements Job {

    private final TenantService tenantService;
    private final SubscriptionService subscriptionService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("Starting BillingCycleJob");
        List<Tenant> tenants = tenantService.getTenantsByStatusList(Status.ACTIVE);

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());

                List<Subscription> expiredSubscriptions = subscriptionService
                        .findAllSubscriptionsByStatusAndPeriodEndBefore(SubscriptionStatus.ACTIVE, Instant.now());

                for (Subscription sub : expiredSubscriptions) {
                    try {
                        // Generate invoice for the completed period
                        var invoice = invoiceService.generateInvoice(
                                sub.getId(), sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd());

                        // Finalize and process payment
                        invoiceService.finalizeInvoice(invoice.getId());
                        paymentService.processPayment(invoice.getId());

                        // Update period dates
                        Instant newStart = sub.getCurrentPeriodEnd();
                        Instant newEnd = switch (sub.getBillingCycle()) {
                            case MONTHLY -> newStart.plus(Duration.ofDays(30));
                            case ANNUAL -> newStart.plus(Duration.ofDays(365));
                        };
                        sub.setCurrentPeriodStart(newStart);
                        sub.setCurrentPeriodEnd(newEnd);
                        subscriptionService.saveSubscription(sub);

                        log.info("Processed billing for subscription {} in tenant {}", sub.getId(),
                                tenant.getSchemaName());
                    } catch (Exception e) {
                        log.error("Error processing billing for subscription {} in tenant {}",
                                sub.getId(), tenant.getSchemaName(), e);
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }
        log.info("BillingCycleJob completed");
    }
}
