package com.mtbs.shared.enums.notification;

/**
 * Event types fired via the transactional outbox.
 *
 * The Subscription/Plan/Invoice/Payment(platform)/Usage/Onboarding groups
 * below are only ever fired by legacy.saasbilling code (archived platform
 * billing) — since that module is excluded from Spring's component scan,
 * those code paths never actually execute in the running app, so these
 * constants are effectively dead weight for the active application. They
 * are kept here (rather than deleted) purely so legacy.saasbilling still
 * compiles against this shared enum. Only BILL_SENT/PAYMENT_RECORDED and
 * the Auth group are used by active code.
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

    // ── Everything below is legacy.saasbilling-only — see class Javadoc ──

    // Subscription events
    TRIAL_STARTED,
    TRIAL_ENDING_SOON,
    TRIAL_EXPIRED,
    SUBSCRIPTION_ACTIVATED,
    SUBSCRIPTION_CANCELLED,
    SUBSCRIPTION_EXPIRED,
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