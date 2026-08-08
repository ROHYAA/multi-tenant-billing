package com.mtbs.billing.controller;

import com.mtbs.billing.dto.subscription.ActivateSubscriptionRequest;
import com.mtbs.billing.dto.subscription.CancelSubscriptionRequest;
import com.mtbs.billing.dto.subscription.CycleChangeRequest;
import com.mtbs.billing.dto.subscription.DowngradeRequest;
import com.mtbs.billing.dto.subscription.SubscriptionOrderResponse;
import com.mtbs.billing.dto.subscription.SubscriptionResponse;
import com.mtbs.billing.dto.subscription.UpgradePreviewResponse;
import com.mtbs.billing.dto.subscription.UpgradeRequest;
import com.mtbs.billing.service.SubscriptionService;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.enums.billing.BillingCycle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription lifecycle endpoints.
 *
 * All write operations require BILLING_MANAGE permission (OWNER role has this).
 * GET /current is open to any authenticated tenant user.
 *
 * ── UPGRADE FLOW ─────────────────────────────────────────────────────────────
 *
 *   1. GET  /upgrade/preview?targetPlanId=2&billingCycle=MONTHLY
 *          → Shows charge, proration, new period. No state change.
 *
 *   2. POST /upgrade/pro      { "billingCycle": "MONTHLY" }
 *      POST /upgrade/enterprise { "billingCycle": "ANNUAL" }
 *          → Creates invoice + Razorpay order.
 *          → Returns SubscriptionOrderResponse with razorpayOrderId, keyId, amount.
 *          → Frontend opens Razorpay checkout modal.
 *
 *   3. POST /api/payments/verify (existing PaymentController endpoint)
 *          → Verifies Razorpay signature.
 *          → PaymentService internally calls SubscriptionService.activateUpgradeAfterPayment().
 *          → Subscription planId is updated only here — never in step 2.
 *
 * ── DOWNGRADE FLOW ───────────────────────────────────────────────────────────
 *
 *   POST /downgrade/free { "atPeriodEnd": true, "reason": "..." }
 *          → atPeriodEnd=true  : FREE takes effect at billing period end (recommended).
 *          → atPeriodEnd=false : FREE takes effect immediately, no refund.
 *
 * ── CYCLE CHANGE FLOW ────────────────────────────────────────────────────────
 *
 *   POST /cycle { "newBillingCycle": "ANNUAL" }
 *          → MONTHLY→ANNUAL: payment required, returns SubscriptionOrderResponse.
 *          → ANNUAL→MONTHLY: no payment, returns SubscriptionResponse (scheduled).
 */
@RestController
@RequestMapping("/api/${api.version}/subscriptions")
@RequiredArgsConstructor
@Tag(
        name = "Subscriptions",
        description = "Subscription lifecycle — current plan, upgrade, downgrade, " +
                "billing cycle change, cancellation, reactivation"
)
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    // ── GET /current ──────────────────────────────────────────────────────────

    @GetMapping("/current")
    @Operation(
            summary = "Get current subscription",
            description = "Returns the tenant's ACTIVE or TRIALING subscription. " +
                    "Includes pending upgrade state, scheduled downgrade, and trial countdown. " +
                    "Returns 404 if no active subscription exists."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getCurrentSubscription() {
        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.getCurrentSubscription(),
                "Subscription fetched successfully"));
    }

    // ── GET /upgrade/preview ──────────────────────────────────────────────────

    @GetMapping("/upgrade/preview")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Preview upgrade cost and proration",
            description = "Returns a full price breakdown before the user commits to an upgrade. " +
                    "Shows: full cycle price, proration credit for unused days on current plan, " +
                    "final charge amount in INR and paise, and the new billing period dates. " +
                    "No state is changed. Call this before showing the Razorpay checkout button."
    )
    public ResponseEntity<ApiResponse<UpgradePreviewResponse>> previewUpgrade(
            @Parameter(description = "ID of the target plan (from GET /api/plans)", required = true)
            @RequestParam Long targetPlanId,
            @Parameter(description = "Billing cycle for the new plan — MONTHLY or ANNUAL", required = true)
            @RequestParam BillingCycle billingCycle) {

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.previewUpgrade(targetPlanId, billingCycle),
                "Upgrade preview fetched successfully"));
    }

    // ── POST /upgrade/pro ─────────────────────────────────────────────────────

    @PostMapping("/upgrade/pro")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Initiate upgrade to Pro",
            description = "Step 1 of the upgrade flow. Creates an OPEN invoice and a Razorpay order " +
                    "for the Pro plan at the requested billing cycle. " +
                    "Returns razorpayOrderId, keyId, and amount in paise — use these to open " +
                    "the Razorpay checkout modal on the frontend. " +
                    "The subscription plan does NOT change until payment is verified via " +
                    "POST /api/payments/verify (Step 2). " +
                    "Blocked if an upgrade is already in progress — complete or cancel it first. " +
                    "Blocked if current subscription is PAST_DUE."
    )
    public ResponseEntity<ApiResponse<SubscriptionOrderResponse>> upgradeToPro(
            @Valid @RequestBody UpgradeRequest request) {

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.initiateUpgradeToPro(request),
                "Upgrade to Pro initiated. Complete payment to activate."));
    }

    // ── POST /upgrade/enterprise ──────────────────────────────────────────────

    @PostMapping("/upgrade/enterprise")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Initiate upgrade to Enterprise",
            description = "Step 1 of the upgrade flow. Creates an OPEN invoice and a Razorpay order " +
                    "for the Enterprise plan at the requested billing cycle. " +
                    "Returns razorpayOrderId, keyId, and amount in paise — use these to open " +
                    "the Razorpay checkout modal on the frontend. " +
                    "The subscription plan does NOT change until payment is verified via " +
                    "POST /api/payments/verify (Step 2). " +
                    "Proration credit is applied if upgrading from a paid plan mid-cycle. " +
                    "Blocked if an upgrade is already in progress — complete or cancel it first."
    )
    public ResponseEntity<ApiResponse<SubscriptionOrderResponse>> upgradeToEnterprise(
            @Valid @RequestBody UpgradeRequest request) {

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.initiateUpgradeToEnterprise(request),
                "Upgrade to Enterprise initiated. Complete payment to activate."));
    }

    // ── POST /downgrade/free ──────────────────────────────────────────────────

    @PostMapping("/downgrade/free")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Downgrade to Free plan",
            description = "Switches the subscription to the Free plan. " +
                    "atPeriodEnd=true (recommended): FREE takes effect when the current billing " +
                    "period expires. User retains Pro/Enterprise features until then. No refund issued. " +
                    "atPeriodEnd=false: FREE takes effect immediately. No refund for unused days. " +
                    "Any pending upgrade in progress is voided before downgrading. " +
                    "Blocked if already on the Free plan."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> downgradeToFree(
            @Valid @RequestBody DowngradeRequest request) {

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.downgradeToFree(request),
                request.getAtPeriodEnd()
                        ? "Downgrade to Free scheduled at end of current billing period."
                        : "Downgraded to Free plan immediately."));
    }

    // ── POST /cycle ───────────────────────────────────────────────────────────

    @PostMapping("/cycle")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Change billing cycle (MONTHLY ↔ ANNUAL)",
            description = "Switches billing frequency on the current plan. " +
                    "MONTHLY → ANNUAL: payment required. Returns SubscriptionOrderResponse " +
                    "with Razorpay checkout data. New 365-day period starts from payment date. " +
                    "ANNUAL → MONTHLY: no payment. Returns SubscriptionResponse with " +
                    "scheduledBillingCycle=MONTHLY — takes effect at next renewal. " +
                    "Blocked if an upgrade is already in progress. " +
                    "Blocked if requesting the same cycle as current."
    )
    public ResponseEntity<ApiResponse<?>> changeBillingCycle(
            @Valid @RequestBody CycleChangeRequest request) {

        Object result = subscriptionService.changeBillingCycle(request);

        if (result instanceof SubscriptionOrderResponse order) {
            return ResponseEntity.ok(ApiResponse.success(order,
                    "Cycle change to ANNUAL initiated. Complete payment to activate."));
        }

        return ResponseEntity.ok(ApiResponse.success((SubscriptionResponse) result,
                "Billing cycle change to MONTHLY scheduled for next renewal."));
    }

    // ── POST /cancel ──────────────────────────────────────────────────────────

    @PostMapping("/cancel")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Cancel subscription",
            description = "Cancels the current ACTIVE or TRIALING subscription. " +
                    "atPeriodEnd=true: marks for cancellation at end of current billing period. " +
                    "Subscription stays ACTIVE — use POST /reactivate to undo. " +
                    "atPeriodEnd=false: cancels immediately. Status becomes CANCELLED. " +
                    "Any pending upgrade is voided before cancelling. " +
                    "Blocked if subscription is already scheduled for cancellation."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> cancelSubscription(
            @Valid @RequestBody CancelSubscriptionRequest request) {

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.cancelSubscription(request.isAtPeriodEnd()),
                request.isAtPeriodEnd()
                        ? "Subscription will be cancelled at end of the current billing period."
                        : "Subscription cancelled immediately."));
    }

    // ── POST /reactivate ──────────────────────────────────────────────────────

    @PostMapping("/reactivate")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Reactivate a scheduled cancellation",
            description = "Undoes a cancel-at-period-end schedule. " +
                    "The subscription continues as normal — no new payment is taken. " +
                    "Billing period remains unchanged. " +
                    "Blocked if the subscription is not actually scheduled for cancellation."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> reactivate() {

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.reactivate(),
                "Subscription reactivated. Cancellation has been undone."));
    }

    // ── POST /activate ────────────────────────────────────────────────────────

    @PostMapping("/activate")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Activate subscription (convert trial to paid)",
            description = "Converts a TRIALING subscription to ACTIVE after payment. " +
                    "Sets the billing cycle and calculates a new billing period from today. " +
                    "Returns 404 if no trialing subscription exists."
    )
    public ResponseEntity<ApiResponse<SubscriptionResponse>> activateSubscription(
            @Valid @RequestBody ActivateSubscriptionRequest request) {

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.activateSubscription(
                        request.getPlanId(),
                        request.getBillingCycle()),
                "Subscription activated successfully."));
    }
}