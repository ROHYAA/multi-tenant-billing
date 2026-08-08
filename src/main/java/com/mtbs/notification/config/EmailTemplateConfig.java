package com.mtbs.notification.config;

import com.mtbs.shared.enums.notification.NotificationEvent;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EmailTemplateConfig {

    @Getter
    public static class TemplateDefinition {
        private final String templateName;
        private final String subject;

        public TemplateDefinition(String templateName, String subject) {
            this.templateName = templateName;
            this.subject = subject;
        }
    }

    private static final Map<NotificationEvent, TemplateDefinition> TEMPLATES = Map.ofEntries(

            // Auth
            Map.entry(NotificationEvent.USER_REGISTERED,
                    new TemplateDefinition("auth/welcome", "Welcome to MTBS — Your account is ready")),
            Map.entry(NotificationEvent.USER_LOGIN,
                    new TemplateDefinition("auth/login-alert", "New login to your account")),
            Map.entry(NotificationEvent.PASSWORD_CHANGED,
                    new TemplateDefinition("auth/password-changed", "Your password was changed")),
            Map.entry(NotificationEvent.PASSWORD_RESET_REQUESTED,
                    new TemplateDefinition("auth/password-reset", "Reset your password")),

            // Onboarding
            Map.entry(NotificationEvent.ONBOARDING_COMPLETED,
                    new TemplateDefinition("onboarding/onboarding-completed", "Welcome to MTBS — Your account is ready")),

            // Trial
            Map.entry(NotificationEvent.TRIAL_STARTED,
                    new TemplateDefinition("subscription/trial-started", "Your free trial has started")),
            Map.entry(NotificationEvent.TRIAL_ENDING_SOON,
                    new TemplateDefinition("subscription/trial-ending", "Your trial ends in 3 days")),
            Map.entry(NotificationEvent.TRIAL_EXPIRED,
                    new TemplateDefinition("subscription/trial-expired", "Your trial has expired")),

            // Subscription
            Map.entry(NotificationEvent.SUBSCRIPTION_ACTIVATED,
                    new TemplateDefinition("subscription/activated", "Subscription activated — Welcome aboard")),
            Map.entry(NotificationEvent.SUBSCRIPTION_CANCELLED,
                    new TemplateDefinition("subscription/cancelled", "Your subscription has been cancelled")),
            Map.entry(NotificationEvent.SUBSCRIPTION_EXPIRED,
                    new TemplateDefinition("subscription/expired", "Your subscription has expired")),
            Map.entry(NotificationEvent.SUBSCRIPTION_RENEWED,
                    new TemplateDefinition("subscription/renewed", "Your subscription has been renewed")),

            // Plan
            Map.entry(NotificationEvent.PLAN_UPGRADED,
                    new TemplateDefinition("plan/upgraded", "You've upgraded your plan")),
            Map.entry(NotificationEvent.PLAN_DOWNGRADED,
                    new TemplateDefinition("plan/downgraded", "Your plan has been downgraded")),

            // Invoice
            Map.entry(NotificationEvent.INVOICE_GENERATED,
                    new TemplateDefinition("billing/invoice-generated", "Invoice {{invoiceNumber}} is ready")),
            Map.entry(NotificationEvent.INVOICE_PAID,
                    new TemplateDefinition("billing/invoice-paid", "Payment received — Invoice {{invoiceNumber}}")),
            Map.entry(NotificationEvent.INVOICE_OVERDUE,
                    new TemplateDefinition("billing/invoice-overdue", "Action required: Invoice {{invoiceNumber}} is overdue")),

            // Payment
            Map.entry(NotificationEvent.PAYMENT_SUCCEEDED,
                    new TemplateDefinition("billing/payment-success", "Payment confirmed — Thank you")),
            Map.entry(NotificationEvent.PAYMENT_FAILED,
                    new TemplateDefinition("billing/payment-failed", "Payment failed — Action required")),
            Map.entry(NotificationEvent.PAYMENT_RETRY,
                    new TemplateDefinition("billing/payment-retry", "Retrying your payment (attempt {{retryAttempt}})")),
            Map.entry(NotificationEvent.PAYMENT_REFUNDED,
                    new TemplateDefinition("billing/payment-refunded", "Refund processed successfully")),

            // Usage
            Map.entry(NotificationEvent.USAGE_LIMIT_WARNING,
                    new TemplateDefinition("usage/limit-warning", "You've used {{usagePercent}}% of your {{metricName}} limit")),
            Map.entry(NotificationEvent.USAGE_LIMIT_REACHED,
                    new TemplateDefinition("usage/limit-reached", "Usage limit reached for {{metricName}}")),

            // Business
            Map.entry(NotificationEvent.BUSINESS_INVOICE_SENT,
                    new TemplateDefinition("business/invoice-sent", "Invoice from {{tenantName}}")),
            Map.entry(NotificationEvent.BUSINESS_PAYMENT_RECORDED,
                    new TemplateDefinition("business/payment-received", "Payment received — {{invoiceNumber}}"))
    );

    public TemplateDefinition getTemplate(NotificationEvent event) {
        TemplateDefinition def = TEMPLATES.get(event);
        if (def == null) {
            throw new IllegalArgumentException("No email template configured for event: " + event);
        }
        return def;
    }

    public TemplateDefinition getTemplate(String eventType) {
        try {
            NotificationEvent event = NotificationEvent.valueOf(eventType);
            return getTemplate(event);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("No email template configured for event: " + eventType);
        }
    }
}