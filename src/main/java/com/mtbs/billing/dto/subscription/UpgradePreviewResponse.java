package com.mtbs.billing.dto.subscription;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.billing.BillingCycle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only preview returned by GET /api/subscriptions/upgrade/preview.
 *
 * The frontend renders this BEFORE showing the Razorpay checkout button
 * so the user knows exactly what they will be charged and why.
 * No state is mutated when this DTO is returned.
 *
 * AMOUNT BREAKDOWN:
 *
 *   FREE → PRO/ENTERPRISE:
 *     No credit. Full price for the chosen billing cycle.
 *     chargeAmount = fullCyclePrice
 *     creditAmount = 0
 *     prorationDays = 0
 *
 *   PRO → ENTERPRISE (within same cycle):
 *     Credit = (remainingDays / totalDays) × currentPlanPrice
 *     Debit  = fullCyclePrice of new plan
 *     chargeAmount = max(debit - credit, 0)   [never negative]
 *     If charge < ₹1 (below Razorpay minimum), upgradeImmediately = true
 *     and no payment is taken — subscription is activated directly.
 *
 *   Any → FREE:
 *     chargeAmount = 0
 *     noChargeReason = "FREE plan has no cost"
 *     downgrade = true — no Razorpay order is created
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpgradePreviewResponse {

    // ── Target plan ───────────────────────────────────────────────────────────

    /** Target plan ID from public.plans */
    private Long targetPlanId;

    /** Display name — e.g. "Pro", "Enterprise" */
    private String targetPlanName;

    /** Billing cycle the user selected */
    private BillingCycle billingCycle;

    /** Full price for the selected billing cycle before any credit */
    private BigDecimal fullCyclePrice;

    // ── Proration (only set for paid→paid upgrades, null for FREE→paid) ──────

    /**
     * Credit amount for unused days on the current paid plan.
     * Null if current plan is FREE or there are no remaining days.
     */
    private BigDecimal creditAmount;

    /**
     * Remaining days on the current billing period used to compute credit.
     * Null for FREE→paid upgrades.
     */
    private Integer remainingDays;

    /**
     * Total days in the current billing period (30 or 365).
     * Null for FREE→paid upgrades.
     */
    private Integer totalPeriodDays;

    // ── Final charge ──────────────────────────────────────────────────────────

    /**
     * Amount the user will actually be charged (in the plan's currency).
     * = fullCyclePrice - creditAmount (floored at 0).
     * Razorpay amounts are in paise — use chargeAmountPaise for the order.
     */
    private BigDecimal chargeAmount;

    /**
     * chargeAmount converted to paise for direct use in Razorpay order creation.
     * chargeAmountPaise = chargeAmount × 100 (rounded to nearest whole paise).
     */
    private Long chargeAmountPaise;

    /** Currency code — "INR", "USD", etc. */
    private String currency;

    // ── Control flags ─────────────────────────────────────────────────────────

    /**
     * True when chargeAmount == 0 (e.g. downgrade to FREE or credit covers
     * the full upgrade amount). No Razorpay order will be created.
     * The frontend should show a "Confirm" button instead of a payment button.
     */
    @Builder.Default
    private boolean noPaymentRequired = false;

    /**
     * Human-readable reason when noPaymentRequired == true.
     * Examples:
     *   "FREE plan has no cost"
     *   "Your proration credit covers the full upgrade amount"
     */
    private String noPaymentReason;

    /**
     * True when the target plan is FREE — this is a downgrade, not an upgrade.
     * The frontend should show downgrade-specific copy ("You will lose access to...").
     */
    @Builder.Default
    private boolean downgrade = false;

    // ── New billing period (after upgrade) ────────────────────────────────────

    /**
     * Start of the new billing period if the upgrade completes.
     * For upgrades: now (immediate effect).
     * For downgrades with atPeriodEnd=true: current period end.
     */
    private Instant newPeriodStart;

    /**
     * End of the new billing period.
     * = newPeriodStart + 30 days (MONTHLY) or + 365 days (ANNUAL)
     */
    private Instant newPeriodEnd;

    // ── Current plan summary (for comparison display) ─────────────────────────

    /** Current plan name — shown in "You are upgrading from X to Y" copy */
    private String currentPlanName;

    /** Current billing cycle */
    private BillingCycle currentBillingCycle;

    /** Current period end date — shown as "Your current plan runs until {date}" */
    private Instant currentPeriodEnd;
}