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
 * Returned by POST /api/subscriptions/upgrade/pro
 * and POST /api/subscriptions/upgrade/enterprise
 * when payment is required.
 *
 * The frontend receives this and uses razorpayOrderId + razorpayKeyId to
 * open the Razorpay checkout widget. After the user pays, the frontend
 * calls POST /api/payments/verify with the payment details.
 *
 * RAZORPAY CHECKOUT INTEGRATION:
 *
 *   var rzp = new Razorpay({
 *     key: response.razorpayKeyId,
 *     order_id: response.razorpayOrderId,
 *     amount: response.chargeAmountPaise,     // in paise
 *     currency: response.currency,
 *     name: "MTBS Platform",
 *     description: response.description,
 *     handler: function(payment) {
 *       // Call POST /api/payments/verify with:
 *       // razorpayOrderId, razorpayPaymentId, razorpaySignature
 *     }
 *   });
 *   rzp.open();
 *
 * STATE MACHINE:
 *   After this response is returned, the subscription has a pending invoice
 *   (status=OPEN, linked via upgradePendingInvoiceId). The subscription plan
 *   does NOT change yet. It changes only after /api/payments/verify succeeds.
 *
 * IDEMPOTENCY:
 *   If the user abandons the checkout and calls the upgrade endpoint again,
 *   the service checks for an existing OPEN invoice linked to this subscription
 *   and returns a new order for the same invoice rather than creating a duplicate.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubscriptionOrderResponse {

    // ── Razorpay checkout data ────────────────────────────────────────────────

    /** Razorpay order ID — pass directly to Razorpay.js as order_id */
    private String razorpayOrderId;

    /** Razorpay publishable key — pass to Razorpay.js as key */
    private String razorpayKeyId;

    /** Amount in paise — pass to Razorpay.js as amount */
    private Long chargeAmountPaise;

    /** Currency code — "INR" */
    private String currency;

    /** Human-readable checkout description — shown in Razorpay modal */
    private String description;

    // ── Invoice context ───────────────────────────────────────────────────────

    /**
     * Internal invoice ID. Store this on the frontend and include it in
     * the POST /api/payments/verify body if needed for tracking.
     * The backend also stores it on the subscription (upgradePendingInvoiceId)
     * so it can be resolved without the frontend sending it back.
     */
    private Long invoiceId;

    /** Invoice number (e.g. INV-1-202603-0001) — for display in UI */
    private String invoiceNumber;

    // ── Upgrade summary ───────────────────────────────────────────────────────

    /** Target plan name — "Pro" or "Enterprise" */
    private String targetPlanName;

    /** Selected billing cycle */
    private BillingCycle billingCycle;

    /** Charge amount as a formatted decimal (for display only) */
    private BigDecimal chargeAmount;

    /** Proration credit applied (null if no proration) */
    private BigDecimal prorationCredit;

    // ── Post-payment preview ──────────────────────────────────────────────────

    /**
     * What the new billing period will be after successful payment.
     * The frontend can show "Your new plan runs from {newPeriodStart} to {newPeriodEnd}"
     * on a confirmation screen.
     */
    private Instant newPeriodStart;
    private Instant newPeriodEnd;

    // ── Expiry ────────────────────────────────────────────────────────────────

    /**
     * When this Razorpay order expires (Razorpay orders expire after 15 minutes
     * by default unless configured otherwise). After this time the user must
     * call the upgrade endpoint again to get a fresh order.
     */
    private Instant orderExpiresAt;
}