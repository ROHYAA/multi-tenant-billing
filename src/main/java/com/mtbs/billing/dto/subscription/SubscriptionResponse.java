package com.mtbs.billing.dto.subscription;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Full subscription state returned by GET /api/subscriptions/current
 * and all mutation endpoints after state change.
 *
 * UI RENDERING GUIDE:
 *
 *   status == TRIALING         → show trial badge, trialEnd countdown
 *   status == ACTIVE           → show plan name, next billing date
 *   status == PAST_DUE         → show overdue warning, retry payment button
 *   status == CANCELLED        → cancelAtPeriodEnd determines active-until date
 *   status == EXPIRED          → show re-subscribe CTA
 *
 *   upgradePending == true     → show "Payment pending" banner
 *                                offer "Complete payment" → reopens Razorpay
 *
 *   cancelAtPeriodEnd == true  → show "Cancels on {currentPeriodEnd}" banner
 *                                offer "Reactivate" button → POST /reactivate
 *
 *   scheduledBillingCycle != null && scheduledBillingCycle != billingCycle
 *                              → show "Switching to {scheduledBillingCycle} on {currentPeriodEnd}"
 *
 *   scheduledDowngradePlanId != null
 *                              → show "Downgrading to FREE on {currentPeriodEnd}"
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubscriptionResponse {

    // ── Core identity ─────────────────────────────────────────────────────────
    private Long id;
    private Long planId;
    private String planName;
    private String planDisplayName;

    // ── Status ────────────────────────────────────────────────────────────────
    private SubscriptionStatus status;
    private BillingCycle billingCycle;

    // ── Trial ─────────────────────────────────────────────────────────────────
    private Instant trialStart;
    private Instant trialEnd;

    /** Days remaining in trial — null when not in trial */
    private Long trialDaysRemaining;

    // ── Billing period ────────────────────────────────────────────────────────
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;

    /** Monthly price of the current plan */
    private BigDecimal priceMonthly;

    /** Annual price of the current plan */
    private BigDecimal priceAnnual;

    /** Currency code */
    private String currency;

    // ── Cancellation ──────────────────────────────────────────────────────────
    private Instant cancelledAt;
    private Boolean cancelAtPeriodEnd;

    // ── Pending upgrade state ─────────────────────────────────────────────────

    /**
     * True when the user has initiated an upgrade but not yet completed payment.
     * The frontend should show a "Complete your payment" banner.
     */
    @Builder.Default
    private boolean upgradePending = false;

    /**
     * The Razorpay order ID for the pending upgrade payment.
     * Non-null only when upgradePending == true.
     * The frontend can use this to re-open the Razorpay checkout modal.
     */
    private String pendingUpgradeOrderId;

    /**
     * The plan name the user is upgrading to (e.g. "Pro").
     * Non-null only when upgradePending == true.
     */
    private String pendingUpgradePlanName;

    // ── Scheduled changes (take effect at period end) ─────────────────────────

    /**
     * Non-null when the user has scheduled a billing cycle change.
     * e.g. user is MONTHLY now, scheduled to switch to ANNUAL next period.
     */
    private BillingCycle scheduledBillingCycle;

    /**
     * Non-null when a downgrade to FREE is scheduled at period end.
     * Value is the plan name (always "Free" in current implementation).
     */
    private String scheduledDowngradePlan;

    /**
     * The reason provided for a scheduled downgrade.
     * Null if no reason was given.
     */
    private String downgradeReason;

    // ── Audit ─────────────────────────────────────────────────────────────────
    private Instant createdAt;
    private Instant updatedAt;
}