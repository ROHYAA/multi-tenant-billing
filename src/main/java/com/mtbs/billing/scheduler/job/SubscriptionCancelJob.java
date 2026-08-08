package com.mtbs.billing.scheduler.job;

import com.mtbs.billing.entity.Subscription;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.billing.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionCancelJob implements Job {

    private final TenantService tenantService;
    private final SubscriptionService subscriptionService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("Starting SubscriptionCancelJob");
        List<Tenant> tenants = tenantService.getTenantsByStatusList(Status.ACTIVE);

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());

                List<Subscription> subscriptionsToCancel = subscriptionService
                        .findAllSubscriptionsByCancelAtPeriodEndAndPeriodEndBefore(Instant.now());

                for (Subscription sub : subscriptionsToCancel) {
                    try {
                        subscriptionService.executeScheduledCancellation(sub.getId());
                        log.info("Cancelled subscription {} at period end for tenant {}",
                                sub.getId(), tenant.getSchemaName());
                    } catch (Exception e) {
                        log.error("Error cancelling subscription {} in tenant {}",
                                sub.getId(), tenant.getSchemaName(), e);
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }
        log.info("SubscriptionCancelJob completed");
    }
}
