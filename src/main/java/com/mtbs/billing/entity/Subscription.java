package com.mtbs.billing.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

@Entity
@Table(name = "subscriptions")
@SQLDelete(sql = "UPDATE subscriptions SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription extends AuditableEntity {

    // ── Core ──────────────────────────────────────────────────────────────────

    /** FK to public.plans — reflects the ACTIVE plan only.
     *  Never updated to the target plan until payment is verified. */
    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.TRIALING;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 50)
    @Builder.Default
    private BillingCycle billingCycle = BillingCycle.MONTHLY;

    // ── Trial ─────────────────────────────────────────────────────────────────

    @Column(name = "trial_start")
    private Instant trialStart;

    @Column(name = "trial_end")
    private Instant trialEnd;

    // ── Billing period ────────────────────────────────────────────────────────

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_at_period_end")
    @Builder.Default
    private Boolean cancelAtPeriodEnd = false;

    // ── Razorpay (subscription-level, set only if Razorpay subscription object exists) ──

    @Column(name = "razorpay_subscription_id")
    private String razorpaySubscriptionId;

    @Column(name = "razorpay_customer_id")
    private String razorpayCustomerId;

    // ── Pending upgrade state (V20) ───────────────────────────────────────────

    /**
     * ID of the OPEN Invoice that must be paid to activate a pending upgrade.
     * Set by SubscriptionService.initiateUpgrade() BEFORE payment is collected.
     * Cleared by SubscriptionService.activateUpgradeAfterPayment() after verify.
     * Cleared by SubscriptionService.voidPendingUpgrade() if user abandons.
     * NULL when no upgrade is pending.
     */
    @Column(name = "upgrade_pending_invoice_id")
    private Long upgradePendingInvoiceId;

    /**
     * Target plan ID for the pending upgrade.
     * planId is NOT changed until payment is verified.
     * NULL when no upgrade is pending.
     */
    @Column(name = "upgrade_pending_plan_id")
    private Long upgradePendingPlanId;

    /**
     * Razorpay order ID for the pending upgrade checkout.
     * Allows the frontend to re-open an abandoned Razorpay modal without
     * creating a duplicate order.
     * NULL when no upgrade is pending.
     */
    @Column(name = "upgrade_pending_razorpay_order_id", length = 64)
    private String upgradePendingRazorpayOrderId;

    // ── Scheduled changes (take effect at period end) (V20) ──────────────────

    /**
     * Billing cycle to switch to at the next period renewal.
     * Set by POST /api/subscriptions/cycle for ANNUAL→MONTHLY.
     * NULL if no cycle change is scheduled.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scheduled_billing_cycle", length = 20)
    private BillingCycle scheduledBillingCycle;

    /**
     * Plan ID to switch to at period end (always FREE plan ID in current impl).
     * Set by POST /api/subscriptions/downgrade/free with atPeriodEnd=true.
     * NULL if no downgrade is scheduled.
     */
    @Column(name = "scheduled_downgrade_plan_id")
    private Long scheduledDowngradePlanId;

    /**
     * Optional user-provided reason for the scheduled downgrade.
     * Stored for audit trail and included in PLAN_DOWNGRADED notification.
     */
    @Column(name = "downgrade_reason", length = 500)
    private String downgradeReason;

    // ── Convenience guards ────────────────────────────────────────────────────

    /** True when an upgrade has been initiated but payment not yet verified. */
    public boolean hasUpgradePending() {
        return upgradePendingInvoiceId != null;
    }

    /** True when a downgrade to FREE is scheduled at period end. */
    public boolean hasScheduledDowngrade() {
        return scheduledDowngradePlanId != null;
    }

    /** True when a billing cycle change is scheduled at period end. */
    public boolean hasScheduledCycleChange() {
        return scheduledBillingCycle != null && scheduledBillingCycle != billingCycle;
    }

    /** Clears all pending upgrade fields atomically. */
    public void clearPendingUpgrade() {
        this.upgradePendingInvoiceId = null;
        this.upgradePendingPlanId = null;
        this.upgradePendingRazorpayOrderId = null;
    }

    /** Clears all scheduled downgrade fields atomically. */
    public void clearScheduledDowngrade() {
        this.scheduledDowngradePlanId = null;
        this.downgradeReason = null;
    }
}