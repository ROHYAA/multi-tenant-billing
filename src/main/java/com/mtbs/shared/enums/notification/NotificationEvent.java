package com.mtbs.shared.enums.notification;

public enum NotificationEvent {

    // Auth events
    USER_REGISTERED,
    USER_LOGIN,
    PASSWORD_CHANGED,
    PASSWORD_RESET_REQUESTED,

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

    // Invoice events
    INVOICE_GENERATED,
    INVOICE_PAID,
    INVOICE_OVERDUE,

    // Payment events
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    PAYMENT_RETRY,
    PAYMENT_REFUNDED,
    PAYMENT_CAPTURED,

    // Usage events
    USAGE_LIMIT_WARNING,
    USAGE_LIMIT_REACHED,

    // Onboarding events
    ONBOARDING_COMPLETED,

    // Business events
    BUSINESS_INVOICE_SENT,
    BUSINESS_PAYMENT_RECORDED
}