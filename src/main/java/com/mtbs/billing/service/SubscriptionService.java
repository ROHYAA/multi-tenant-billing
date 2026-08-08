package com.mtbs.billing.service;

import com.mtbs.billing.dto.subscription.CycleChangeRequest;
import com.mtbs.billing.dto.subscription.DowngradeRequest;
import com.mtbs.billing.dto.subscription.SubscriptionOrderResponse;
import com.mtbs.billing.dto.subscription.SubscriptionResponse;
import com.mtbs.billing.dto.subscription.UpgradePreviewResponse;
import com.mtbs.billing.dto.subscription.UpgradeRequest;
import com.mtbs.billing.dto.OrderResponse;
import com.mtbs.billing.dto.InvoiceResponse;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.billing.mapper.SubscriptionMapper;
import com.mtbs.billing.repository.SubscriptionRepository;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import com.mtbs.shared.event.billing.PaymentCapturedEvent;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.exception.SubscriptionException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.repository.PlanRepository;
import com.mtbs.tenant.service.PlanService;
import com.mtbs.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the full subscription lifecycle for a tenant.
 *
 * ── ENDPOINT MAP ─────────────────────────────────────────────────────────────
 *
 *   GET  /api/subscriptions/current          → getCurrentSubscription()
 *   GET  /api/subscriptions/upgrade/preview  → previewUpgrade()
 *   POST /api/subscriptions/upgrade/pro      → initiateUpgradeToPro()
 *   POST /api/subscriptions/upgrade/enterprise → initiateUpgradeToEnterprise()
 *   POST /api/subscriptions/downgrade/free   → downgradeToFree()
 *   POST /api/subscriptions/cycle            → changeBillingCycle()
 *   POST /api/subscriptions/cancel           → cancelSubscription()
 *   POST /api/subscriptions/reactivate       → reactivate()
 *   POST /api/subscriptions/activate         → activateSubscription()  [trial → paid]
 *
 * ── UPGRADE FLOW (2-step) ────────────────────────────────────────────────────
 *
 *   Step 1: initiateUpgrade*() creates OPEN Invoice + Razorpay order,
 *           sets upgradePendingInvoiceId + upgradePendingPlanId on subscription,
 *           returns SubscriptionOrderResponse for frontend Razorpay checkout.
 *
 *   Step 2: POST /api/payments/verify (PaymentService) verifies signature,
 *           calls activateUpgradeAfterPayment(invoiceId) which swaps planId,
 *           clears pending upgrade fields, fires PLAN_UPGRADED notification.
 *
 * ── INVARIANTS ───────────────────────────────────────────────────────────────
 *
 *   - planId on Subscription only changes AFTER payment is verified.
 *   - upgradePendingInvoiceId != null  ↔  an upgrade checkout is in flight.
 *   - cancelAtPeriodEnd=true never co-exists with upgradePending=true.
 *   - Scheduled downgrade is cleared if tenant upgrades before period end.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanService planService;
    private final PlanRepository planRepository;
    private final TenantService tenantService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final ProrationService prorationService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final SubscriptionMapper subscriptionMapper;

    @Value("${razorpay.key-id}")
    private String razorpayKeyId;

    // ================== Cross-Module Query Methods ==================

    @Transactional(readOnly = true)
    public Optional<Subscription> findSubscriptionById(Long id) {
        return subscriptionRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> findSubscriptionByIdOrThrow(Long id) {
        return Optional.of(subscriptionRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Subscription", id)));
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> findFirstSubscriptionByStatuses(List<SubscriptionStatus> statuses) {
        return subscriptionRepository.findFirstByStatusIn(statuses);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findAllSubscriptionsByStatusAndPeriodEndBefore(SubscriptionStatus status, Instant threshold) {
        return subscriptionRepository.findAllByStatusAndCurrentPeriodEndBefore(status, threshold);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findAllSubscriptionsByStatusAndTrialEndBefore(SubscriptionStatus status, Instant threshold) {
        return subscriptionRepository.findAllByStatusAndTrialEndBefore(status, threshold);
    }

    @Transactional(readOnly = true)
    public long countSubscriptionsByStatus(SubscriptionStatus status) {
        return subscriptionRepository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findAllSubscriptionsByCancelAtPeriodEndAndPeriodEndBefore(Instant now) {
        return subscriptionRepository.findAllByCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(now);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findAllSubscriptionsByScheduledDowngradeAndPeriodEndBefore(SubscriptionStatus status, Instant now) {
        return subscriptionRepository.findAllByStatusAndScheduledDowngradePlanIdIsNotNullAndCurrentPeriodEndBefore(status, now);
    }

    @Transactional
    public Subscription saveSubscription(Subscription subscription) {
        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId) {
        return subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> findByUpgradePendingInvoiceId(Long invoiceId) {
        return subscriptionRepository.findByUpgradePendingInvoiceId(invoiceId);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public SubscriptionResponse getCurrentSubscription() {
        return mapToSubscriptionResponse(requireActiveOrTrialing());
    }

    /**
     * Returns the current active or trialing entity, or null.
     * Used by PlanLimitService, schedulers, and internal callers.
     */
    public Subscription getCurrentSubscriptionEntity() {
        return subscriptionRepository
                .findFirstByStatusIn(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING))
                .orElse(null);
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Returns a full price/proration breakdown before the user commits to an upgrade.
     * No DB writes. Called by GET /api/subscriptions/upgrade/preview.
     *
     * @param targetPlanId ID of the plan to upgrade/downgrade to
     * @param cycle        requested billing cycle
     */
    public UpgradePreviewResponse previewUpgrade(Long targetPlanId, BillingCycle cycle) {
        Subscription current = requireActiveOrTrialing();
        return prorationService.buildPreview(current, targetPlanId, cycle);
    }

    // ── Upgrade — Step 1 (creates invoice + Razorpay order) ──────────────────

    /**
     * Initiates an upgrade to the PRO plan.
     * Creates an OPEN invoice, a Razorpay order, and marks the subscription
     * with the pending upgrade details.
     *
     * Returns SubscriptionOrderResponse — the frontend opens Razorpay checkout,
     * then calls POST /api/payments/verify on success.
     */
    @Transactional
    public SubscriptionOrderResponse initiateUpgradeToPro(UpgradeRequest request) {
        Plan proPlan = planService.getPlanEntityByCode("PRO");
        return initiateUpgrade(proPlan, request.getBillingCycle());
    }

    /**
     * Initiates an upgrade to the ENTERPRISE plan.
     */
    @Transactional
    public SubscriptionOrderResponse initiateUpgradeToEnterprise(UpgradeRequest request) {
        Plan enterprisePlan = planService.getPlanEntityByCode("ENTERPRISE");
        return initiateUpgrade(enterprisePlan, request.getBillingCycle());
    }

    // ── Upgrade — Step 2 (called by PaymentService after verify) ─────────────

    /**
     * Activates a pending upgrade after payment is verified by Razorpay.
     *
     * Called by PaymentService.handleSubscriptionUpgradePayment(invoiceId)
     * which is triggered after POST /api/payments/verify succeeds.
     *
     * This method:
     *   1. Finds the subscription linked to the paid invoice
     *   2. Swaps planId to the pending target plan
     *   3. Sets new billing period starting from now
     *   4. Clears all pending upgrade fields
     *   5. Clears any scheduled downgrade (upgrade wins)
     *   6. Fires PLAN_UPGRADED notification
     *
     * @param invoiceId the invoice that was just paid
     */
    @CacheEvict(value = "dashboard", allEntries = true)
    @Transactional
    public void activateUpgradeAfterPayment(Long invoiceId) {
        Subscription subscription = subscriptionRepository
                .findByUpgradePendingInvoiceId(invoiceId)
                .orElseThrow(() -> ResourceException.notFound(
                        "Subscription with pending upgrade for invoice", invoiceId));

        Plan oldPlan = planService.getPlanById(subscription.getPlanId());
        Plan newPlan = planService.getPlanById(subscription.getUpgradePendingPlanId());

        Instant now = Instant.now();
        Instant periodEnd = subscription.getBillingCycle() == BillingCycle.MONTHLY
                ? now.plus(Duration.ofDays(30))
                : now.plus(Duration.ofDays(365));

        // Activate the upgrade
        subscription.setPlanId(newPlan.getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(periodEnd);

        // Clear pending upgrade — atomically via convenience method on entity
        subscription.clearPendingUpgrade();

        // Upgrade cancels any scheduled downgrade
        subscription.clearScheduledDowngrade();
        subscription.setCancelAtPeriodEnd(false);
        subscription.setCancelledAt(null);

        subscriptionRepository.save(subscription);

        log.info("Upgrade activated — from={}, to={}, cycle={}, tenantId={}",
                oldPlan.getName(), newPlan.getName(),
                subscription.getBillingCycle(), TenantContext.getTenantId());

        fireUpgradeEvent(NotificationEvent.PLAN_UPGRADED, oldPlan, newPlan,
                subscription, invoiceId);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.SUBSCRIPTION_UPDATED)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(subscription.getId())
                .whoUserId(TenantContext.getTenantId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("planId", oldPlan.getId(), "planName", oldPlan.getName()))
                .changesAfter(Map.of("planId", newPlan.getId(), "planName", newPlan.getName()))
                .description("Subscription upgraded from " + oldPlan.getName() + " to " + newPlan.getName())
                .module("BILLING")
                .build(), "Subscription", subscription.getId());
    }

    // ── Downgrade to FREE ─────────────────────────────────────────────────────

    /**
     * Downgrades the current subscription to the FREE plan.
     *
     * atPeriodEnd=true  → scheduled: FREE takes effect when current period expires.
     *                     User retains PRO/ENTERPRISE features until then.
     *                     No refund. SubscriptionExpiryJob executes at period end.
     *
     * atPeriodEnd=false → immediate: FREE takes effect now.
     *                     No refund for unused period. Rare — admin use case.
     *
     * Rejects FREE → FREE downgrade attempts.
     */
    @CacheEvict(value = "dashboard", allEntries = true)
    @Transactional
    public SubscriptionResponse downgradeToFree(DowngradeRequest request) {
        Subscription subscription = requireActiveOrTrialing();
        Plan currentPlan = planService.getPlanById(subscription.getPlanId());

        if ("FREE".equalsIgnoreCase(currentPlan.getName())) {
            throw SubscriptionException.downgradeNotApplicable(currentPlan.getName());
        }

        // Void any pending upgrade before downgrading
        if (subscription.hasUpgradePending()) {
            voidPendingUpgrade(subscription);
        }

        Plan freePlan = planService.getPlanEntityByCode("FREE");

        if (Boolean.TRUE.equals(request.getAtPeriodEnd())) {
            // Scheduled downgrade — set fields, SubscriptionExpiryJob executes later
            subscription.setScheduledDowngradePlanId(freePlan.getId());
            subscription.setDowngradeReason(request.getReason());
            subscriptionRepository.save(subscription);

            log.info("Downgrade to FREE scheduled at {} — tenantId={}",
                    subscription.getCurrentPeriodEnd(), TenantContext.getTenantId());
        } else {
            // Immediate downgrade
            subscription.setPlanId(freePlan.getId());
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.clearScheduledDowngrade();
            Instant now = Instant.now();
            subscription.setCurrentPeriodStart(now);
            subscription.setCurrentPeriodEnd(now.plus(Duration.ofDays(365)));
            subscriptionRepository.save(subscription);

            log.info("Immediate downgrade to FREE — tenantId={}", TenantContext.getTenantId());
        }

        fireSimpleEvent(NotificationEvent.PLAN_DOWNGRADED, freePlan, subscription);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.SUBSCRIPTION_UPDATED)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(subscription.getId())
                .whoUserId(TenantContext.getTenantId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("planId", currentPlan.getId(), "planName", currentPlan.getName()))
                .changesAfter(Map.of("planId", freePlan.getId(), "planName", "FREE"))
                .description("Subscription downgraded from " + currentPlan.getName() + " to FREE")
                .module("BILLING")
                .build(), "Subscription", subscription.getId());

        return mapToSubscriptionResponse(subscriptionRepository.findById(subscription.getId())
                .orElseThrow());
    }

    // ── Billing cycle change ──────────────────────────────────────────────────

    /**
     * Changes the billing cycle on the current plan.
     *
     * MONTHLY → ANNUAL: requires payment (full annual price minus proration credit).
     *   Returns SubscriptionOrderResponse — same 2-step payment flow as upgrade.
     *   New period of 365 days starts from payment date.
     *
     * ANNUAL → MONTHLY: no payment needed, scheduled at period end.
     *   Returns SubscriptionResponse with scheduledBillingCycle set.
     *   BillingCycleJob switches cycle at renewal.
     *
     * Rejects same-cycle requests.
     */
    @Transactional
    public Object changeBillingCycle(CycleChangeRequest request) {
        Subscription subscription = requireActive();
        BillingCycle current = subscription.getBillingCycle();
        BillingCycle requested = request.getNewBillingCycle();

        if (current == requested) {
            throw SubscriptionException.sameCycle(current.name());
        }

        if (subscription.hasUpgradePending()) {
            throw SubscriptionException.upgradePending(
                    subscription.getUpgradePendingRazorpayOrderId());
        }

        Plan currentPlan = planService.getPlanById(subscription.getPlanId());

        if (requested == BillingCycle.ANNUAL) {
            // MONTHLY → ANNUAL: payment required
            return initiateUpgrade(currentPlan, BillingCycle.ANNUAL);
        } else {
            // ANNUAL → MONTHLY: scheduled at period end, no payment
            subscription.setScheduledBillingCycle(BillingCycle.MONTHLY);
            subscriptionRepository.save(subscription);
            log.info("Billing cycle change ANNUAL→MONTHLY scheduled at {} — tenantId={}",
                    subscription.getCurrentPeriodEnd(), TenantContext.getTenantId());
            return mapToSubscriptionResponse(subscription);
        }
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Cancels the current subscription.
     *
     * atPeriodEnd=true  → sets cancelAtPeriodEnd flag; subscription stays ACTIVE
     *                     and SubscriptionExpiryJob cancels it at period end.
     * atPeriodEnd=false → cancels immediately (status = CANCELLED).
     *
     * Blocked if subscription is already scheduled for cancellation.
     * Blocked if subscription is not ACTIVE or TRIALING.
     */
    @Transactional
    public SubscriptionResponse cancelSubscription(boolean atPeriodEnd) {
        Subscription subscription = subscriptionRepository
                .findFirstByStatusIn(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING))
                .orElseThrow(SubscriptionException::notFound);

        if (subscription.getCancelAtPeriodEnd()) {
            throw SubscriptionException.alreadyCancelled();
        }

        // Void any pending upgrade if cancelling
        if (subscription.hasUpgradePending()) {
            voidPendingUpgrade(subscription);
        }

        if (atPeriodEnd) {
            subscription.setCancelAtPeriodEnd(true);
            subscription.setCancelledAt(Instant.now());
        } else {
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            subscription.setCancelledAt(Instant.now());
        }

        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Subscription cancelled — atPeriodEnd={}, tenantId={}",
                atPeriodEnd, TenantContext.getTenantId());

        Plan plan = planService.getPlanById(saved.getPlanId());
        fireSimpleEvent(NotificationEvent.SUBSCRIPTION_CANCELLED, plan, saved);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.CANCEL)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(saved.getId())
                .whoUserId(TenantContext.getTenantId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", subscription.getStatus().name()))
                .changesAfter(Map.of("status", saved.getStatus().name(), "atPeriodEnd", atPeriodEnd))
                .description("Subscription cancelled (atPeriodEnd=" + atPeriodEnd + ")")
                .module("BILLING")
                .severity("WARN")
                .build(), "Subscription", saved.getId());

        return mapToSubscriptionResponse(saved);
    }

    // ── Reactivate ────────────────────────────────────────────────────────────

    /**
     * Undoes a cancel-at-period-end schedule.
     * The subscription continues as normal — no new payment is taken.
     * Blocked if subscription is not actually scheduled for cancellation.
     */
    @Transactional
    public SubscriptionResponse reactivate() {
        Subscription subscription = requireActiveOrTrialing();

        if (!Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd())) {
            throw ResourceException.invalid(
                    "Subscription is not scheduled for cancellation. Nothing to reactivate.");
        }

        subscription.setCancelAtPeriodEnd(false);
        subscription.setCancelledAt(null);
        Subscription saved = subscriptionRepository.save(subscription);

        log.info("Subscription reactivated — tenantId={}", TenantContext.getTenantId());

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(saved.getId())
                .whoUserId(TenantContext.getTenantId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .description("Subscription reactivated (cancel-at-period-end undone)")
                .module("BILLING")
                .build(), "Subscription", saved.getId());

        return mapToSubscriptionResponse(saved);
    }

    // ── Activate (trial → paid) ───────────────────────────────────────────────

    /**
     * Converts a TRIALING subscription to ACTIVE after payment.
     * Called from POST /api/subscriptions/activate (existing endpoint).
     */
    @Transactional
    public SubscriptionResponse activateSubscription(Long planId, BillingCycle cycle) {
        Subscription subscription = subscriptionRepository
                .findFirstByStatusIn(List.of(SubscriptionStatus.TRIALING))
                .orElseThrow(() -> ResourceException.notFound("Subscription", "trialing"));

        Plan plan = planService.getPlanById(planId);
        Instant now = Instant.now();
        Instant periodEnd = cycle == BillingCycle.MONTHLY
                ? now.plus(Duration.ofDays(30))
                : now.plus(Duration.ofDays(365));

        subscription.setPlanId(planId);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setBillingCycle(cycle);
        subscription.setCurrentPeriodStart(now);
        subscription.setCurrentPeriodEnd(periodEnd);

        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Subscription activated from trial — plan={}, cycle={}", plan.getName(), cycle);

        fireSimpleEvent(NotificationEvent.SUBSCRIPTION_ACTIVATED, plan, saved);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(saved.getId())
                .whoUserId(TenantContext.getTenantId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", "TRIALING"))
                .changesAfter(Map.of("status", "ACTIVE", "planId", plan.getId(), "planName", plan.getName()))
                .description("Trial subscription activated to " + plan.getName())
                .module("BILLING")
                .build(), "Subscription", saved.getId());

        return mapToSubscriptionResponse(saved);
    }

    // ── Scheduler-only ────────────────────────────────────────────────────────

    /** Called by TrialExpiryJob and SubscriptionExpiryJob. */
    @Transactional
    public void expireSubscription(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> ResourceException.notFound("Subscription", subscriptionId));
        sub.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(sub);

        Plan plan = planService.getPlanById(sub.getPlanId());
        fireSimpleEvent(NotificationEvent.SUBSCRIPTION_EXPIRED, plan, sub);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(sub.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", sub.getStatus().name()))
                .changesAfter(Map.of("status", SubscriptionStatus.EXPIRED.name()))
                .description("Subscription expired")
                .module("BILLING")
                .severity("WARN")
                .build(), "Subscription", sub.getId());

        log.info("Subscription expired: id={}", subscriptionId);
    }

    /**
     * Called by TrialExpiryJob when trial ends without payment.
     * Generates invoice for trial usage and sets status to PAST_DUE.
     * Gives tenant grace period to pay before subscription expires.
     */
    @CacheEvict(value = "dashboard", allEntries = true)
    @Transactional
    public void markTrialAsPastDue(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> ResourceException.notFound("Subscription", subscriptionId));
        
        Plan plan = planService.getPlanById(sub.getPlanId());
        sub.setStatus(SubscriptionStatus.PAST_DUE);
        subscriptionRepository.save(sub);

        log.info("Trial marked as PAST_DUE — subscriptionId={}", subscriptionId);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(sub.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", SubscriptionStatus.TRIALING.name()))
                .changesAfter(Map.of("status", SubscriptionStatus.PAST_DUE.name()))
                .description("Trial expired - payment required within grace period")
                .module("BILLING")
                .severity("WARN")
                .build(), "Subscription", sub.getId());
    }

    /** Called by SubscriptionExpiryJob after grace period expires. */
    @Transactional
    public void suspendForNonPayment(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> ResourceException.notFound("Subscription", subscriptionId));
        sub.setStatus(SubscriptionStatus.PAST_DUE);
        subscriptionRepository.save(sub);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(sub.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", sub.getStatus().name()))
                .changesAfter(Map.of("status", SubscriptionStatus.PAST_DUE.name()))
                .description("Subscription suspended for non-payment")
                .module("BILLING")
                .severity("WARN")
                .build(), "Subscription", sub.getId());

        log.info("Subscription PAST_DUE for non-payment: id={}", subscriptionId);
    }

    /**
     * Called by SubscriptionExpiryJob when a scheduled downgrade's period expires.
     * Switches planId to the scheduled FREE plan and resets to a 1-year period.
     */
    @CacheEvict(value = "dashboard", allEntries = true)
    @Transactional
    public void executeScheduledDowngrade(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> ResourceException.notFound("Subscription", subscriptionId));

        if (!sub.hasScheduledDowngrade()) {
            log.warn("executeScheduledDowngrade called but no downgrade scheduled — id={}", subscriptionId);
            return;
        }

        Plan freePlan = planService.getPlanById(sub.getScheduledDowngradePlanId());
        Instant now = Instant.now();

        sub.setPlanId(freePlan.getId());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(now.plus(Duration.ofDays(365)));
        sub.clearScheduledDowngrade();
        sub.setScheduledBillingCycle(null); // reset cycle schedule too
        subscriptionRepository.save(sub);

        fireSimpleEvent(NotificationEvent.PLAN_DOWNGRADED, freePlan, sub);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.SUBSCRIPTION_UPDATED)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(sub.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("planId", freePlan.getId(), "planName", "FREE"))
                .description("Scheduled downgrade to FREE executed")
                .module("BILLING")
                .build(), "Subscription", sub.getId());

        log.info("Scheduled downgrade to FREE executed — subscriptionId={}", subscriptionId);
    }

    /**
     * Called by BillingCycleJob when an ANNUAL subscription renews and
     * scheduledBillingCycle = MONTHLY. Switches cycle for the new period.
     */
    @Transactional
    public void applyScheduledCycleChange(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> ResourceException.notFound("Subscription", subscriptionId));

        if (!sub.hasScheduledCycleChange()) {
            return;
        }

        sub.setBillingCycle(sub.getScheduledBillingCycle());
        sub.setScheduledBillingCycle(null);
        subscriptionRepository.save(sub);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.SUBSCRIPTION_UPDATED)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(sub.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("billingCycle", sub.getBillingCycle().name()))
                .description("Billing cycle changed to " + sub.getBillingCycle().name())
                .module("BILLING")
                .build(), "Subscription", sub.getId());

        log.info("Billing cycle changed to {} — subscriptionId={}",
                sub.getBillingCycle(), subscriptionId);
    }

    /** Called by SubscriptionCancelJob when cancelAtPeriodEnd=true and period has ended. */
    @CacheEvict(value = "dashboard", allEntries = true)
    @Transactional
    public void executeScheduledCancellation(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> ResourceException.notFound("Subscription", subscriptionId));

        if (!Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
            log.warn("executeScheduledCancellation called but cancelAtPeriodEnd is false — id={}", subscriptionId);
            return;
        }

        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setCancelAtPeriodEnd(false);
        sub.setCancelledAt(Instant.now());
        subscriptionRepository.save(sub);

        Plan plan = planService.getPlanById(sub.getPlanId());
        fireSimpleEvent(NotificationEvent.SUBSCRIPTION_CANCELLED, plan, sub);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.CANCEL)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(sub.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("status", SubscriptionStatus.CANCELLED.name()))
                .description("Scheduled subscription cancellation executed")
                .module("BILLING")
                .severity("WARN")
                .build(), "Subscription", sub.getId());

        log.info("Scheduled cancellation executed — subscriptionId={}", subscriptionId);
    }

    /**
     * Called by OnboardingCompletionService only.
     * Creates first subscription for a new tenant.
     */
    @Transactional
    public SubscriptionResponse startTrial(Long planId) {
        subscriptionRepository.findFirstByStatusIn(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING)
        ).ifPresent(s -> {
            throw ResourceException.alreadyExists("Subscription", "active subscription already exists");
        });

        Plan plan = planService.getPlanById(planId);
        Instant now = Instant.now();

        if (planService.getTrialDaysForPlan(plan.getId(), BillingCycle.MONTHLY) == null || planService.getTrialDaysForPlan(plan.getId(), BillingCycle.MONTHLY) == 0) {
            Subscription sub = Subscription.builder()
                    .planId(planId)
                    .status(SubscriptionStatus.ACTIVE)
                    .billingCycle(BillingCycle.MONTHLY)
                    .currentPeriodStart(now)
                    .currentPeriodEnd(now.plus(Duration.ofDays(365)))
                    .build();
            return mapToSubscriptionResponse(subscriptionRepository.save(sub));
        }

        Subscription sub = Subscription.builder()
                .planId(planId)
                .status(SubscriptionStatus.TRIALING)
                .billingCycle(BillingCycle.MONTHLY)
                .trialStart(now)
                .trialEnd(now.plus(Duration.ofDays(planService.getTrialDaysForPlan(plan.getId(), BillingCycle.MONTHLY))))
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plus(Duration.ofDays(planService.getTrialDaysForPlan(plan.getId(), BillingCycle.MONTHLY))))
                .build();

        Subscription saved = subscriptionRepository.save(sub);
        log.info("Trial started — plan={}, days={}", plan.getName(), planService.getTrialDaysForPlan(plan.getId(), BillingCycle.MONTHLY));
        fireSimpleEvent(NotificationEvent.TRIAL_STARTED, plan, saved);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.SUBSCRIPTION_CREATED)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(saved.getId())
                .whoUserId(TenantContext.getTenantId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("planId", plan.getId(), "planName", plan.getName(), "status", saved.getStatus().name()))
                .description("Trial subscription started: " + plan.getName())
                .module("BILLING")
                .build(), "Subscription", saved.getId());

        return mapToSubscriptionResponse(saved);
    }

    /**
     * Called by AuthService during old-style tenant registration.
     * Idempotent — skips silently if already subscribed.
     */
    @Transactional
    public SubscriptionResponse autoSubscribeToFreePlan() {
        subscriptionRepository.findFirstByStatusIn(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING)
        ).ifPresent(s -> {
            throw ResourceException.alreadyExists("Subscription", "already has active subscription");
        });

        Plan freePlan = planRepository.findByName("FREE")
                .orElseThrow(() -> ResourceException.notFound("Plan", "FREE"));

        Instant now = Instant.now();
        Subscription sub = Subscription.builder()
                .planId(freePlan.getId())
                .status(SubscriptionStatus.ACTIVE)
                .billingCycle(BillingCycle.MONTHLY)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plus(Duration.ofDays(365)))
                .build();

        Subscription saved = subscriptionRepository.save(sub);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.SUBSCRIPTION_CREATED)
                .entityType(AuditEntityType.SUBSCRIPTION)
                .entityId(saved.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("planId", freePlan.getId(), "planName", "FREE", "status", SubscriptionStatus.ACTIVE.name()))
                .description("Auto-subscribed to FREE plan")
                .module("BILLING")
                .build(), "Subscription", saved.getId());

        return mapToSubscriptionResponse(saved);
    }

    // ── Public mapping (used by TenantBillingDashboardService) ───────────────

    public SubscriptionResponse mapToSubscriptionResponse(Subscription sub) {
        Plan plan = planService.getPlanById(sub.getPlanId());
        SubscriptionResponse response = subscriptionMapper.toResponseWithPlan(sub, plan);
        response.setPriceMonthly(planService.getPriceMonthly(plan.getId()));
        response.setPriceAnnual(planService.getPriceAnnual(plan.getId()));
        response.setCurrency(planService.getCurrencyForPlan(plan.getId()));
        if (sub.getStatus() == SubscriptionStatus.TRIALING && sub.getTrialEnd() != null) {
            long days = ChronoUnit.DAYS.between(Instant.now(), sub.getTrialEnd());
            response.setTrialDaysRemaining(Math.max(days, 0));
        }

        if (sub.hasUpgradePending() && sub.getUpgradePendingPlanId() != null) {
            try {
                response.setPendingUpgradePlanName(
                        planService.getPlanById(sub.getUpgradePendingPlanId()).getDisplayName());
            } catch (Exception ignored) { }
        }

        if (sub.hasScheduledDowngrade()) {
            try {
                response.setScheduledDowngradePlan(
                        planService.getPlanById(sub.getScheduledDowngradePlanId()).getDisplayName());
            } catch (Exception ignored) { }
        }

        return response;
    }

    public SubscriptionResponse mapToResponse(Subscription sub) {
        return mapToSubscriptionResponse(sub);
    }

    // ── Private: core upgrade flow ────────────────────────────────────────────

    /**
     * Core upgrade initiation shared by initiateUpgradeToPro()
     * and initiateUpgradeToEnterprise() and MONTHLY→ANNUAL cycle change.
     *
     * Validates state, calculates charge, creates invoice, creates Razorpay order,
     * stamps pending upgrade fields on the subscription.
     */
    private SubscriptionOrderResponse initiateUpgrade(Plan targetPlan, BillingCycle cycle) {
        Subscription subscription = requireActiveOrTrialing();

        // Guard: upgrade already in flight
        if (subscription.hasUpgradePending()) {
            throw SubscriptionException.upgradePending(
                    subscription.getUpgradePendingRazorpayOrderId());
        }

        // Guard: already on this plan (same plan, cycle change goes to changeBillingCycle)
        if (subscription.getPlanId().equals(targetPlan.getId())
                && subscription.getBillingCycle() == cycle) {
            throw ResourceException.invalid(
                    "You are already on the " + targetPlan.getDisplayName()
                            + " plan with " + cycle.name() + " billing.");
        }

        // Guard: cannot upgrade from PAST_DUE — resolve payment first
        if (subscription.getStatus() == SubscriptionStatus.PAST_DUE) {
            throw SubscriptionException.invalidTransition(
                    subscription.getStatus().name(), targetPlan.getName());
        }

        BigDecimal chargeAmount = prorationService.calculateChargeAmount(
                subscription, targetPlan, cycle);

        // Build new billing period (used for preview; actual period set after payment)
        Instant newPeriodStart = Instant.now();
        Instant newPeriodEnd = cycle == BillingCycle.MONTHLY
                ? newPeriodStart.plus(Duration.ofDays(30))
                : newPeriodStart.plus(Duration.ofDays(365));

        // Create invoice for the upgrade charge
        InvoiceResponse invoice = invoiceService.generateInvoice(
                subscription.getId(), newPeriodStart, newPeriodEnd);
        invoiceService.finalizeInvoice(invoice.getId());

        // Create Razorpay order
        long amountPaise = prorationService.toPaise(chargeAmount);
        String receipt = "upgrade-" + TenantContext.getTenantId() + "-" + invoice.getId();
        OrderResponse order = paymentService.processPayment(invoice.getId());

        // Stamp pending upgrade on subscription — planId unchanged until payment verified
        subscription.setUpgradePendingInvoiceId(invoice.getId());
        subscription.setUpgradePendingPlanId(targetPlan.getId());
        subscription.setUpgradePendingRazorpayOrderId(order.getOrderId());
        subscriptionRepository.save(subscription);

        log.info("Upgrade initiated — from={}, to={}, cycle={}, charge={}INR, orderId={}, tenantId={}",
                planService.getPlanById(subscription.getPlanId()).getName(),
                targetPlan.getName(), cycle, chargeAmount,
                order.getOrderId(), TenantContext.getTenantId());

        BigDecimal prorationCredit = null;
        if (!"FREE".equalsIgnoreCase(planService.getPlanById(subscription.getPlanId()).getName())) {
            BigDecimal fullPrice = cycle == BillingCycle.MONTHLY
                    ? planService.getPriceMonthly(targetPlan.getId()) : planService.getPriceAnnual(targetPlan.getId());
            if (fullPrice != null && chargeAmount.compareTo(fullPrice) < 0) {
                prorationCredit = fullPrice.subtract(chargeAmount);
            }
        }

        return SubscriptionOrderResponse.builder()
                .razorpayOrderId(order.getOrderId())
                .razorpayKeyId(razorpayKeyId)
                .chargeAmountPaise(amountPaise)
                .currency(order.getCurrency())
                .description("Upgrade to " + targetPlan.getDisplayName() + " (" + cycle.name() + ")")
                .invoiceId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .targetPlanName(targetPlan.getDisplayName())
                .billingCycle(cycle)
                .chargeAmount(chargeAmount)
                .prorationCredit(prorationCredit)
                .newPeriodStart(newPeriodStart)
                .newPeriodEnd(newPeriodEnd)
                .orderExpiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                .build();
    }

    /**
     * Voids the pending upgrade invoice and clears upgrade state.
     * Called when the user cancels, downgrades, or re-initiates an upgrade
     * while one is already in flight.
     */
    private void voidPendingUpgrade(Subscription subscription) {
        try {
            invoiceService.voidInvoice(subscription.getUpgradePendingInvoiceId());
        } catch (Exception e) {
            log.warn("Could not void pending upgrade invoice {}: {}",
                    subscription.getUpgradePendingInvoiceId(), e.getMessage());
        }
        subscription.clearPendingUpgrade();
    }

    // ── Private: guards ───────────────────────────────────────────────────────

    /**
     * Listens for successful payment capture.
     * If the paid invoice is linked to a pending upgrade, activates it.
     *
     * Runs synchronously in the SAME transaction as verifyAndCapturePayment()
     * because @TransactionalEventListener(BEFORE_COMMIT) inherits the caller's tx.
     * If activation fails → entire payment verify rolls back. ✅ Same guarantee.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCaptured(PaymentCapturedEvent event) {
        subscriptionRepository.findByUpgradePendingInvoiceId(event.getInvoiceId())
                .ifPresent(subscription -> {
                    log.info("Invoice {} was a subscription upgrade — activating for subscriptionId={}",
                            event.getInvoiceId(), subscription.getId());
                    activateUpgradeAfterPayment(event.getInvoiceId());
                });
    }

    private Subscription requireActiveOrTrialing() {
        return subscriptionRepository
                .findFirstByStatusIn(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING))
                .orElseThrow(SubscriptionException::notFound);
    }

    private Subscription requireActive() {
        return subscriptionRepository
                .findFirstByStatusIn(List.of(SubscriptionStatus.ACTIVE))
                .orElseThrow(() -> ResourceException.notFound("Subscription", "active"));
    }

    // ── Private: event firing ─────────────────────────────────────────────────

    private void fireSimpleEvent(NotificationEvent type, Plan plan, Subscription sub) {
        try {
            Long tenantId = TenantContext.getTenantId();
            Tenant tenant = tenantService.getTenantById(tenantId);

            outboxEventPublisher.save(BillingEvent.builder()
                    .eventType(type)
                    .tenantId(tenantId)
                    .tenantName(tenant != null ? tenant.getName() : tenantService.fetchTenantName())
                    .recipientEmail(tenant != null ? tenant.getOwnerEmail() : null)
                    .recipientName(tenant != null ? tenant.getName() : null)
                    .planName(plan.getDisplayName())
                    .planPrice(planService.getPriceMonthly(plan.getId()))
                    .billingCycle(sub.getBillingCycle() != null ? sub.getBillingCycle().name() : null)
                    .trialEndsAt(sub.getTrialEnd())
                    .subscriptionEndsAt(sub.getCurrentPeriodEnd())
                    .build(), "Subscription", sub.getId());
        } catch (Exception e) {
            log.warn("Failed to fire {} event: {}", type, e.getMessage());
        }
    }

    private void fireUpgradeEvent(NotificationEvent type, Plan oldPlan, Plan newPlan,
                                  Subscription sub, Long invoiceId) {
        try {
            Long tenantId = TenantContext.getTenantId();
            Tenant tenant = tenantService.getTenantById(tenantId);

            outboxEventPublisher.save(BillingEvent.builder()
                    .eventType(type)
                    .tenantId(tenantId)
                    .tenantName(tenant != null ? tenant.getName() : tenantService.fetchTenantName())
                    .recipientEmail(tenant != null ? tenant.getOwnerEmail() : null)
                    .recipientName(tenant != null ? tenant.getName() : null)
                    .planName(newPlan.getDisplayName())
                    .oldPlanName(oldPlan.getDisplayName())
                    .planPrice(planService.getPriceMonthly(newPlan.getId()))
                    .billingCycle(sub.getBillingCycle().name())
                    .nextBillingDate(sub.getCurrentPeriodEnd())
                    .build(), "Subscription", sub.getId());
        } catch (Exception e) {
            log.warn("Failed to fire upgrade event: {}", type, e.getMessage());
    }
}
}