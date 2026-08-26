package com.mtbs.shared.enums.notification;

/**
 * Event types fired via the transactional outbox.
 *
 * Most of the Subscription/Plan/Invoice/Payment(platform)/Usage/Onboarding
 * groups below are only ever fired by legacy.saasbilling code (archived
 * platform billing) — since that module is excluded from Spring's component
 * scan, those code paths never actually execute in the running app, so
 * those constants are effectively dead weight for the active application.
 * They are kept here (rather than deleted) purely so legacy.saasbilling
 * still compiles against this shared enum. Active code uses
 * BILL_SENT/PAYMENT_RECORDED, the Auth group, and — as of the offline-
 * payment subscription-tracking feature (SubscriptionExpiryJob) —
 * SUBSCRIPTION_EXPIRING_SOON/SUBSCRIPTION_EXPIRED, which are genuinely
 * shared between legacy and active use despite living in this list.
 */
public enum NotificationEvent {

    // Auth events (active)
    USER_REGISTERED,
    USER_LOGIN,
    PASSWORD_CHANGED,
    PASSWORD_RESET_REQUESTED,

    // Retail bill events (active)
    BILL_SENT,
    PAYMENT_RECORDED,

    // Offline-payment subscription tracking (active) — see SubscriptionExpiryJob.
    SUBSCRIPTION_EXPIRING_SOON,
    SUBSCRIPTION_EXPIRED,

    // ── Everything below is legacy.saasbilling-only — see class Javadoc ──

    // Subscription events
    TRIAL_STARTED,
    TRIAL_ENDING_SOON,
    TRIAL_EXPIRED,
    SUBSCRIPTION_ACTIVATED,
    SUBSCRIPTION_CANCELLED,
    SUBSCRIPTION_RENEWED,

    // Plan events
    PLAN_UPGRADED,
    PLAN_DOWNGRADED,

    // Invoice events (platform)
    INVOICE_GENERATED,
    INVOICE_PAID,
    INVOICE_OVERDUE,

    // Payment events (platform)
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    PAYMENT_RETRY,
    PAYMENT_REFUNDED,
    PAYMENT_CAPTURED,

    // Usage events
    USAGE_LIMIT_WARNING,
    USAGE_LIMIT_REACHED,

    // Onboarding events
    ONBOARDING_COMPLETED
}