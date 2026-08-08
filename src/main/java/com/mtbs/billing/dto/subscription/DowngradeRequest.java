package com.mtbs.billing.dto.subscription;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for POST /api/subscriptions/downgrade/free.
 *
 * DOWNGRADE SEMANTICS:
 *
 *   atPeriodEnd = true (strongly recommended, should be default in the UI):
 *     - FREE plan takes effect when the current billing period expires
 *     - User retains PRO/ENTERPRISE features until currentPeriodEnd
 *     - No refund is issued — the paid period runs to completion
 *     - Subscription status stays ACTIVE until period end, then
 *       SubscriptionExpiryJob switches it to FREE
 *
 *   atPeriodEnd = false (immediate downgrade):
 *     - FREE plan takes effect immediately
 *     - No refund is issued for the unused portion of the billing period
 *     - Use with caution — reserved for admin-initiated forced downgrades
 *     - The controller should restrict this to OWNER role only
 *
 * The reason field is optional but recommended — stored for audit purposes
 * and included in the PLAN_DOWNGRADED notification email.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DowngradeRequest {

    /**
     * true  — downgrade takes effect at end of current billing period (recommended)
     * false — downgrade takes effect immediately (no refund)
     */
    @NotNull(message = "atPeriodEnd is required")
    private Boolean atPeriodEnd;

    /**
     * Optional reason for the downgrade.
     * Stored for audit trail and included in downgrade notification email.
     * Examples: "Too expensive", "Not using the features", "Switching to competitor"
     */
    private String reason;
}