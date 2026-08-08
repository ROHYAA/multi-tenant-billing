package com.mtbs.billing.scheduler.job;

import com.mtbs.billing.entity.Payment;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.billing.PaymentStatus;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.billing.repository.PaymentRepository;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.billing.service.PaymentService;
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
public class PaymentRetryJob implements Job {

    private final TenantService tenantService;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("Starting PaymentRetryJob");
        List<Tenant> tenants = tenantService.getTenantsByStatusList(Status.ACTIVE);

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());

                List<Payment> failedPayments = paymentRepository
                        .findAllByStatusAndNextRetryAtBefore(PaymentStatus.FAILED, Instant.now());

                for (Payment payment : failedPayments) {
                    if (payment.getRetryCount() < 3) {
                        try {
                            paymentService.retryFailedPayment(payment.getId());
                            log.info("Retried payment {} (attempt {})", payment.getId(), payment.getRetryCount() + 1);
                        } catch (Exception e) {
                            log.error("Error retrying payment {} in tenant {}",
                                    payment.getId(), tenant.getSchemaName(), e);
                        }
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }
        log.info("PaymentRetryJob completed");
    }
}
