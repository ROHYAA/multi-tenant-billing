package com.mtbs.billing.dto.subscription;

import com.mtbs.shared.enums.billing.BillingCycle;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for POST /api/subscriptions/cycle.
 *
 * Switches billing frequency on the SAME plan.
 * Valid transitions: MONTHLY → ANNUAL or ANNUAL → MONTHLY.
 * Passing the same cycle as the current one throws a 400 BAD_REQUEST.
 *
 * CYCLE CHANGE SEMANTICS:
 *
 *   MONTHLY → ANNUAL:
 *     - Creates a new invoice for the annual price
 *     - Payment required via Razorpay (same 2-step flow as upgrade)
 *     - New period = 365 days from payment date
 *     - Proration credit for remaining monthly days is applied to annual price
 *     - Returns SubscriptionOrderResponse for frontend to open Razorpay checkout
 *
 *   ANNUAL → MONTHLY:
 *     - Takes effect at end of current annual period (no refund for unused months)
 *     - No payment required now
 *     - Returns updated SubscriptionResponse with scheduledCycle = MONTHLY
 *     - The scheduler switches to MONTHLY on the next renewal
 *
 * This DTO is intentionally minimal — the service derives everything else
 * from the current subscription state.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CycleChangeRequest {

    @NotNull(message = "New billing cycle is required — MONTHLY or ANNUAL")
    private BillingCycle newBillingCycle;
}