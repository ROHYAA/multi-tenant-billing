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

    /**
     * Templates for events fired by legacy.saasbilling (Subscription/Plan/
     * Invoice/Payment(platform)/Usage/Onboarding) were removed along with
     * their template files — that code path never runs (legacy is excluded
     * from Spring's component scan), so those templates would never be
     * requested. See NotificationEvent's Javadoc for the full list of
     * enum constants that no longer have a template mapping here.
     */
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

            // Retail bills
            Map.entry(NotificationEvent.BILL_SENT,
                    new TemplateDefinition("bill/invoice-sent", "Invoice from {{tenantName}}")),
            Map.entry(NotificationEvent.PAYMENT_RECORDED,
                    new TemplateDefinition("bill/payment-received", "Payment received — {{invoiceNumber}}"))
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