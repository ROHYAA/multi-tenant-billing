package com.mtbs.shared.exception;

/**
 * Typed exception for subscription lifecycle errors.
 *
 * All error codes are in the SUB_7xxx range defined in {@link ErrorCode}.
 * Each factory method carries a specific, actionable message — never generic
 * "something went wrong" text.
 *
 * GlobalExceptionHandler handles BaseException uniformly so no additional
 * handler registration is needed.
 */
public class SubscriptionException extends BaseException {

    public SubscriptionException(ErrorCode errorCode) {
        super(errorCode);
    }

    public SubscriptionException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * No ACTIVE or TRIALING subscription exists for this tenant.
     * Thrown by getCurrentSubscription() and any operation that requires one.
     */
    public static SubscriptionException notFound() {
        return new SubscriptionException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
    }

    /**
     * Tenant already has a pending upgrade invoice that hasn't been paid.
     * They must complete the existing Razorpay checkout or the service must
     * void the pending invoice before a new upgrade can be initiated.
     *
     * @param pendingOrderId the existing Razorpay order ID — include in message
     *                       so the frontend can re-open the existing checkout
     */
    public static SubscriptionException upgradePending(String pendingOrderId) {
        return new SubscriptionException(
                ErrorCode.SUBSCRIPTION_UPGRADE_PENDING,
                "An upgrade payment is already in progress (order: " + pendingOrderId + "). " +
                "Complete the payment or call POST /api/subscriptions/upgrade/cancel to void it.");
    }

    /**
     * The requested plan transition is not valid from the current subscription state.
     * Examples: EXPIRED → PRO without going through FREE first,
     *           PAST_DUE attempting an upgrade before resolving outstanding payment.
     *
     * @param currentStatus current subscription status as a string
     * @param targetPlan    name of the plan being targeted
     */
    public static SubscriptionException invalidTransition(String currentStatus, String targetPlan) {
        return new SubscriptionException(
                ErrorCode.SUBSCRIPTION_INVALID_TRANSITION,
                "Cannot change to '" + targetPlan + "' from status '" + currentStatus + "'. " +
                "Resolve any outstanding issues with your current subscription first.");
    }

    /**
     * cancelAtPeriodEnd is already true — the subscription is already scheduled
     * for cancellation. Calling cancel again is a no-op conflict.
     */
    public static SubscriptionException alreadyCancelled() {
        return new SubscriptionException(
                ErrorCode.SUBSCRIPTION_ALREADY_CANCELLED,
                "Your subscription is already scheduled for cancellation at the end of the billing period. " +
                "To undo this, call POST /api/subscriptions/reactivate.");
    }

    /**
     * Only ACTIVE and TRIALING subscriptions can be cancelled.
     * Trying to cancel a CANCELLED, EXPIRED, or PAST_DUE subscription is invalid.
     *
     * @param currentStatus current subscription status
     */
    public static SubscriptionException notCancellable(String currentStatus) {
        return new SubscriptionException(
                ErrorCode.SUBSCRIPTION_NOT_CANCELLABLE,
                "Cannot cancel a subscription with status '" + currentStatus + "'. " +
                "Only ACTIVE or TRIALING subscriptions can be cancelled.");
    }

    /**
     * The requested billing cycle is the same as the current one.
     * Thrown by POST /api/subscriptions/cycle when newBillingCycle == current.
     *
     * @param cycle the billing cycle that is already active
     */
    public static SubscriptionException sameCycle(String cycle) {
        return new SubscriptionException(
                ErrorCode.SUBSCRIPTION_SAME_CYCLE,
                "You are already on the " + cycle + " billing cycle. " +
                "Choose a different cycle to switch.");
    }

    /**
     * Downgrade to FREE is only valid from PRO or ENTERPRISE.
     * FREE tenants cannot downgrade further.
     *
     * @param currentPlanName name of the current plan
     */
    public static SubscriptionException downgradeNotApplicable(String currentPlanName) {
        return new SubscriptionException(
                ErrorCode.SUBSCRIPTION_DOWNGRADE_NOT_APPLICABLE,
                "Cannot downgrade from '" + currentPlanName + "' to Free. " +
                "Downgrade is only available for Pro and Enterprise plans.");
    }
}