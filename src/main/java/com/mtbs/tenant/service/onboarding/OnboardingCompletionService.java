package com.mtbs.tenant.service.onboarding;

import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.entity.TenantOnboarding;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.repository.TenantOnboardingRepository;
import com.mtbs.tenant.repository.TenantRepository;
import com.mtbs.billing.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Handles the final step of onboarding — flipping the tenant ACTIVE and
 * provisioning the first subscription in the tenant's schema.
 *
 * CRITICAL THREADING NOTE:
 *   TenantContext.setTenantId() is called here BEFORE the @Transactional
 *   boundary on SubscriptionService.startTrial() / activateSubscription()
 *   opens. This is mandatory — Hibernate reads the schema from TenantContext
 *   at connection acquisition time, not at method entry.
 *
 *   TenantContext is cleared by JwtAuthenticationFilter in its finally block,
 *   so we do NOT clear it here. Clearing it here would wipe the context
 *   before the filter's finally block runs, leaving the connection routed
 *   to the wrong schema for any subsequent Hibernate work in the same request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingCompletionService {

    private final TenantRepository tenantRepository;
    private final TenantOnboardingRepository onboardingRepository;
    private final SubscriptionService subscriptionService;
    private final OutboxEventPublisher outboxEventPublisher;

    /**
     * Completes onboarding:
     * 1. Sets TenantContext to the tenant's schema
     * 2. Creates subscription (with trial if applicable) in the tenant schema
     * 3. Marks tenant ACTIVE and onboarding_step = 3 in public schema
     * 4. Marks onboarding record completed
     * 5. Fires ONBOARDING_COMPLETED notification event
     */
    public void completeOnboarding(Long tenantId, Plan plan, BillingCycle billingCycle) {
        log.info("Completing onboarding for tenantId={}, plan={}", tenantId, plan.getName());

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ResourceException.notFound("Tenant", tenantId));

        TenantOnboarding onboarding = onboardingRepository.findByTenantId(tenantId)
                .orElseThrow(() -> ResourceException.notFound("OnboardingRecord", tenantId));

        // ── Step into the tenant schema ────────────────────────────────────────
        // Must happen before any @Transactional that touches tenant schema tables.
        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(tenant.getSchemaName());

        try {
            provisionSubscription(plan, billingCycle);
        } catch (Exception e) {
            log.error("Failed to provision subscription for tenantId={}", tenantId, e);
            // Do NOT clear TenantContext — the filter's finally block handles that
            throw e;
        }

        // ── Activate tenant in public schema ──────────────────────────────────
        activateTenant(tenant, onboarding, plan);

        // ── Fire notification ─────────────────────────────────────────────────
        fireOnboardingCompleteEvent(tenant, onboarding, plan);

        log.info("Onboarding completed successfully for tenantId={}", tenantId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void provisionSubscription(Plan plan, BillingCycle billingCycle) {
        // startTrial() already handles both cases correctly:
        // - plan.trialDays == 0 (FREE) → creates ACTIVE subscription directly
        // - plan.trialDays > 0 (PRO/ENTERPRISE) → creates TRIALING subscription
        // activateSubscription() must NOT be called here — it expects an existing
        // TRIALING row to convert, which doesn't exist for a brand-new tenant.
        subscriptionService.startTrial(plan.getId());
    }


    @Transactional
    public void activateTenant(Tenant tenant, TenantOnboarding onboarding, Plan plan) {
        tenant.setStatus(Status.ACTIVE);
        tenant.setOnboardingStep(3);
        tenant.setPlan(plan);
        tenantRepository.save(tenant);

        onboarding.setCompletedAt(Instant.now());
        onboardingRepository.save(onboarding);
    }

    private void fireOnboardingCompleteEvent(Tenant tenant, TenantOnboarding onboarding, Plan plan) {
        try {
            outboxEventPublisher.save(
                    BillingEvent.builder()
                            .eventType(NotificationEvent.ONBOARDING_COMPLETED)
                            .recipientEmail(tenant.getOwnerEmail())
                            .recipientName(tenant.getName())
                            .tenantId(tenant.getId())
                            .tenantName(tenant.getName())
                            .planName(plan.getName())
                            .build(),
                    "Tenant",
                    tenant.getId()
            );
        } catch (Exception e) {
            // Notification failure must never roll back the completion
            log.warn("Failed to fire ONBOARDING_COMPLETED event for tenantId={}: {}",
                    tenant.getId(), e.getMessage());
        }
    }
}