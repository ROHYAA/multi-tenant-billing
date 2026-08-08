package com.mtbs.billing.repository;

import com.mtbs.billing.entity.Subscription;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    // ── Existing methods (unchanged) ──────────────────────────────────────────

    Optional<Subscription> findFirstByStatusIn(List<SubscriptionStatus> statuses);

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    List<Subscription> findAllByStatusAndCurrentPeriodEndBefore(
            SubscriptionStatus status, Instant threshold);

    List<Subscription> findAllByStatusAndTrialEndBefore(
            SubscriptionStatus status, Instant threshold);

    long countByStatus(SubscriptionStatus status);

    // ── New: upgrade activation lookup ────────────────────────────────────────

    /**
     * Finds the subscription that has a pending upgrade linked to the given invoice.
     *
     * Called by SubscriptionService.activateUpgradeAfterPayment(Long invoiceId)
     * after PaymentService verifies the Razorpay payment.
     *
     * This is the bridge between the payment world (invoice paid) and the
     * subscription world (plan upgrade takes effect). The subscriptionId is not
     * stored on the Invoice entity — we link via upgradePendingInvoiceId instead
     * to keep the billing flow loosely coupled.
     *
     * Spring Data derives the query automatically from the field name:
     *   WHERE upgrade_pending_invoice_id = ? AND deleted = false
     * (deleted = false is enforced by @SQLRestriction on the entity)
     */
    Optional<Subscription> findByUpgradePendingInvoiceId(Long invoiceId);

    // ── New: scheduler — process scheduled downgrades at period end ───────────

    /**
     * Finds ACTIVE subscriptions that have a scheduled downgrade AND whose
     * current billing period has already ended.
     *
     * Called by SubscriptionExpiryJob (hourly) to execute pending downgrades.
     * Only ACTIVE subscriptions are checked — CANCELLED/EXPIRED don't need
     * downgrade processing.
     */
    List<Subscription> findAllByStatusAndScheduledDowngradePlanIdIsNotNullAndCurrentPeriodEndBefore(
            SubscriptionStatus status, Instant now);

    // ── Scheduler — cancel-at-period-end ─────────────────────────────────────

    /**
     * Finds ACTIVE subscriptions with cancelAtPeriodEnd=true AND currentPeriodEnd < now.
     *
     * Called by SubscriptionCancelJob (hourly) to execute end-of-period cancellations.
     */
    List<Subscription> findAllByCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(Instant now);
}