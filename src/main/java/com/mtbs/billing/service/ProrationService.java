package com.mtbs.billing.service;

import com.mtbs.billing.dto.subscription.UpgradePreviewResponse;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Encapsulates all proration and upgrade amount calculations.
 *
 * This service is pure computation — it reads plan prices and subscription
 * state but NEVER writes to the database. All mutations happen in
 * SubscriptionService after the user pays.
 *
 * ── PRORATION MODEL ──────────────────────────────────────────────────────────
 *
 *   FREE → any paid plan:
 *     No proration credit (FREE costs nothing).
 *     chargeAmount = fullCyclePrice of target plan.
 *
 *   TRIALING → any paid plan:
 *     Trial has no monetary value. No credit.
 *     chargeAmount = fullCyclePrice of target plan.
 *
 *   Paid → higher paid plan (same cycle):
 *     credit = (remainingDays / totalPeriodDays) × currentPlanCyclePrice
 *     charge = max(targetPlanCyclePrice - credit, 0)
 *
 *   Paid MONTHLY → same plan ANNUAL:
 *     credit = (remainingDays / 30) × currentPlanMonthlyPrice
 *     charge = max(targetPlanAnnualPrice - credit, 0)
 *
 *   Paid → FREE:
 *     chargeAmount = 0 (no refund, no charge)
 *     downgrade = true
 *
 * ── RAZORPAY MINIMUM ─────────────────────────────────────────────────────────
 *
 *   Razorpay requires a minimum order amount of ₹1 (100 paise).
 *   If the computed charge is > 0 but < ₹1, we round up to ₹1 to avoid
 *   a gateway rejection. This edge case only occurs when a tenant upgrades
 *   with a single day left in their billing period.
 *
 * ── SCALE & ROUNDING ─────────────────────────────────────────────────────────
 *
 *   All intermediate calculations use HALF_UP at 6 decimal places.
 *   Final amounts are rounded to 2 decimal places (paise = amount × 100,
 *   truncated to Long).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProrationService {

    // Razorpay minimum: ₹1 = 100 paise
    private static final BigDecimal RAZORPAY_MIN_AMOUNT = BigDecimal.ONE;
    private static final int MONTHLY_DAYS = 30;
    private static final int ANNUAL_DAYS  = 365;

    private final PlanService planService;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds a full preview of what the user will be charged for a plan upgrade.
     * Called by GET /api/subscriptions/upgrade/preview.
     * No DB writes — pure read + calculation.
     *
     * @param current     the tenant's current active subscription
     * @param targetPlanId ID of the plan to upgrade/downgrade to
     * @param cycle       requested billing cycle (MONTHLY or ANNUAL)
     * @return populated UpgradePreviewResponse ready to return to the frontend
     */
    public UpgradePreviewResponse buildPreview(Subscription current,
                                               Long targetPlanId,
                                               BillingCycle cycle) {

        Plan currentPlan = planService.getPlanById(current.getPlanId());
        Plan targetPlan  = planService.getPlanById(targetPlanId);

        validateUpgradeTarget(current, targetPlan);

        boolean isDowngradeToFree = "FREE".equalsIgnoreCase(targetPlan.getName());

        BigDecimal fullCyclePrice = resolveFullCyclePrice(targetPlan, cycle);
        BigDecimal credit         = calculateCredit(current, currentPlan, cycle);
        BigDecimal chargeAmount   = isDowngradeToFree
                ? BigDecimal.ZERO
                : applyRazorpayMinimum(fullCyclePrice.subtract(credit).max(BigDecimal.ZERO));

        boolean noPaymentRequired = chargeAmount.compareTo(BigDecimal.ZERO) == 0;

        Instant newPeriodStart = Instant.now();
        Instant newPeriodEnd   = cycle == BillingCycle.MONTHLY
                ? newPeriodStart.plus(MONTHLY_DAYS, ChronoUnit.DAYS)
                : newPeriodStart.plus(ANNUAL_DAYS,  ChronoUnit.DAYS);

        int remainingDays = remainingDays(current);
        int totalDays     = totalPeriodDays(current.getBillingCycle());

        String noPaymentReason = null;
        if (isDowngradeToFree) {
            noPaymentReason = "Downgrading to the Free plan has no cost.";
        } else if (noPaymentRequired && credit.compareTo(BigDecimal.ZERO) > 0) {
            noPaymentReason = "Your proration credit covers the full upgrade amount.";
        }

        log.debug("Upgrade preview: currentPlan={}, targetPlan={}, cycle={}, " +
                  "fullCyclePrice={}, credit={}, charge={}",
                currentPlan.getName(), targetPlan.getName(), cycle,
                fullCyclePrice, credit, chargeAmount);

        return UpgradePreviewResponse.builder()
                .targetPlanId(targetPlan.getId())
                .targetPlanName(targetPlan.getDisplayName())
                .billingCycle(cycle)
                .fullCyclePrice(fullCyclePrice)
                .creditAmount(credit.compareTo(BigDecimal.ZERO) > 0 ? credit : null)
                .remainingDays(credit.compareTo(BigDecimal.ZERO) > 0 ? remainingDays : null)
                .totalPeriodDays(credit.compareTo(BigDecimal.ZERO) > 0 ? totalDays : null)
                .chargeAmount(chargeAmount)
                .chargeAmountPaise(toPaise(chargeAmount))
                .currency(resolveCurrency(targetPlan))
                .noPaymentRequired(noPaymentRequired)
                .noPaymentReason(noPaymentReason)
                .downgrade(isDowngradeToFree)
                .newPeriodStart(newPeriodStart)
                .newPeriodEnd(newPeriodEnd)
                .currentPlanName(currentPlan.getDisplayName())
                .currentBillingCycle(current.getBillingCycle())
                .currentPeriodEnd(current.getCurrentPeriodEnd())
                .build();
    }

    /**
     * Calculates the amount to charge in rupees for an upgrade.
     * Used directly by SubscriptionService.initiateUpgrade() to create
     * the Razorpay order amount.
     *
     * @param current       current subscription
     * @param targetPlan    target plan entity
     * @param cycle         requested billing cycle
     * @return charge amount in INR (BigDecimal, 2 decimal places)
     *         Returns ZERO for FREE target or when credit covers full cost.
     */
    public BigDecimal calculateChargeAmount(Subscription current,
                                            Plan targetPlan,
                                            BillingCycle cycle) {
        if ("FREE".equalsIgnoreCase(targetPlan.getName())) {
            return BigDecimal.ZERO;
        }

        Plan currentPlan  = planService.getPlanById(current.getPlanId());
        BigDecimal fullCyclePrice = resolveFullCyclePrice(targetPlan, cycle);
        BigDecimal credit         = calculateCredit(current, currentPlan, cycle);
        BigDecimal raw            = fullCyclePrice.subtract(credit).max(BigDecimal.ZERO);

        return applyRazorpayMinimum(raw);
    }

    /**
     * Converts a rupee amount to paise for Razorpay order creation.
     * Razorpay API requires amounts in smallest currency unit (paise for INR).
     *
     * @param rupees amount in INR
     * @return amount in paise (multiply by 100, round HALF_UP, convert to Long)
     */
    public long toPaise(BigDecimal rupees) {
        if (rupees == null || rupees.compareTo(BigDecimal.ZERO) == 0) {
            return 0L;
        }
        return rupees
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * Computes the number of days remaining in the current billing period.
     * Used in the proration credit calculation.
     *
     * @param subscription current subscription
     * @return days remaining (minimum 0 — never negative)
     */
    public int remainingDays(Subscription subscription) {
        if (subscription.getCurrentPeriodEnd() == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(Instant.now(), subscription.getCurrentPeriodEnd());
        return (int) Math.max(days, 0);
    }

    // ── Private calculation helpers ───────────────────────────────────────────

    /**
     * Calculates the proration credit for the unused portion of the current plan.
     *
     * Credit is only applicable when:
     *   - The current plan is a PAID plan (not FREE)
     *   - There are remaining days in the billing period
     *   - The subscription is ACTIVE (not TRIALING — trial has no monetary value)
     *
     * Formula:
     *   dailyRate = currentPlanCyclePrice / totalPeriodDays
     *   credit    = dailyRate × remainingDays
     *   (rounded HALF_UP to 2 decimal places)
     */
    private BigDecimal calculateCredit(Subscription current,
                                       Plan currentPlan,
                                       BillingCycle targetCycle) {
        // No credit for FREE or TRIALING — these have no monetary value
        if ("FREE".equalsIgnoreCase(currentPlan.getName())) {
            return BigDecimal.ZERO;
        }
        if (current.getStatus() != null) {
            switch (current.getStatus()) {
                case TRIALING, EXPIRED, CANCELLED, PAST_DUE, UNPAID -> {
                    return BigDecimal.ZERO;
                }
                default -> { /* ACTIVE — continue to calculate credit */ }
            }
        }

        int remaining  = remainingDays(current);
        if (remaining <= 0) {
            return BigDecimal.ZERO;
        }

        int totalDays               = totalPeriodDays(current.getBillingCycle());
        BigDecimal currentCyclePrice = resolveFullCyclePrice(currentPlan, current.getBillingCycle());

        if (currentCyclePrice == null || currentCyclePrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // dailyRate = currentCyclePrice / totalDays  (6dp for precision)
        BigDecimal dailyRate = currentCyclePrice.divide(
                BigDecimal.valueOf(totalDays), 6, RoundingMode.HALF_UP);

        // credit = dailyRate × remainingDays  (2dp for currency)
        BigDecimal credit = dailyRate.multiply(BigDecimal.valueOf(remaining))
                .setScale(2, RoundingMode.HALF_UP);

        log.debug("Proration credit: currentPlan={}, cycle={}, remaining={}/{} days, " +
                  "dailyRate={}, credit={}",
                currentPlan.getName(), current.getBillingCycle(),
                remaining, totalDays, dailyRate, credit);

        return credit;
    }

    /**
     * Returns the full price for a plan + billing cycle combination.
     * Throws ResourceException.invalid() if the plan has no price configured
     * for the requested cycle (should never happen with seeded plans).
     */
    private BigDecimal resolveFullCyclePrice(Plan plan, BillingCycle cycle) {
        BigDecimal price = switch (cycle) {
            case MONTHLY -> planService.getPriceMonthly(plan.getId());
            case ANNUAL  -> planService.getPriceAnnual(plan.getId());
        };

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw ResourceException.invalid(
                    "Plan '" + plan.getName() + "' has no price configured for cycle: " + cycle);
        }
        return price;
    }

    /**
     * Total days in a billing period — used as the denominator for proration.
     * MONTHLY = 30 days (simplified fixed month, consistent with period creation)
     * ANNUAL  = 365 days
     */
    private int totalPeriodDays(BillingCycle cycle) {
        return cycle == BillingCycle.ANNUAL ? ANNUAL_DAYS : MONTHLY_DAYS;
    }

    /**
     * If chargeAmount is positive but below Razorpay's ₹1 minimum, round up
     * to ₹1. This prevents order creation failures at the gateway.
     * If chargeAmount is ZERO, return ZERO (no payment required — no order created).
     */
    private BigDecimal applyRazorpayMinimum(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) > 0
                && amount.compareTo(RAZORPAY_MIN_AMOUNT) < 0) {
            log.debug("Charge {} is below Razorpay minimum — rounding up to ₹1", amount);
            return RAZORPAY_MIN_AMOUNT;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns currency from the plan, defaulting to "INR" if not configured.
     * All current plans use INR but this guards against null if a future plan
     * has a null currency column.
     */
    private String resolveCurrency(Plan plan) {
        return (planService.getCurrencyForPlan(plan.getId()) != null && planService.getCurrencyForPlan(plan.getId()).isBlank())
                ? planService.getCurrencyForPlan(plan.getId())
                : "INR";
    }

    /**
     * Guards against invalid upgrade combinations before doing any math.
     *
     * BLOCKED:
     *   - Upgrading to the SAME plan (use /cycle endpoint for cycle change)
     *   - Target plan is not active
     *
     * ALLOWED but handled differently:
     *   - Any plan → FREE  (downgrade — no payment, treated separately in service)
     *   - FREE → any paid  (no credit, full price)
     *   - TRIALING → paid  (no credit, full price)
     *   - Paid → higher paid (proration credit applied)
     */
    private void validateUpgradeTarget(Subscription current, Plan targetPlan) {
        if (Boolean.FALSE.equals(targetPlan.getIsActive())) {
            throw ResourceException.invalid(
                    "Plan '" + targetPlan.getName() + "' is not available.");
        }
        if (current.getPlanId().equals(targetPlan.getId())) {
            throw ResourceException.invalid(
                    "You are already on the '" + targetPlan.getName() + "' plan. " +
                    "To change billing cycle, use POST /api/subscriptions/cycle.");
        }
    }
}