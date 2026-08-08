package com.mtbs.notification.service;

import com.mtbs.notification.application.NotificationOrchestrator;
import com.mtbs.notification.config.EmailTemplateConfig;
import com.mtbs.notification.config.NotificationProperties;
import com.mtbs.notification.domain.NotificationChannel;
import com.mtbs.notification.domain.NotificationPriority;
import com.mtbs.notification.domain.NotificationRequest;
import com.mtbs.shared.event.auth.AuthNotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationOrchestrator orchestrator;
    private final EmailTemplateConfig templateConfig;
    private final NotificationProperties properties;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a z")
                    .withZone(ZoneId.of("UTC"));

    public void handleBillingEvent(BillingEvent event) {
        log.info("Processing billing notification: type={}, tenant={}", event.getEventType(), event.getTenantId());
        try {
            EmailTemplateConfig.TemplateDefinition def = templateConfig.getTemplate(event.getEventType());
            String resolvedSubject = resolveSubject(def.getSubject(), event);
            Map<String, Object> variables = buildBillingVariables(event);

            String tenantId = event.getTenantId() != null ? event.getTenantId().toString() : null;

            byte[] pdfBytes = extractPdfBytes(event);
            String pdfFileName = null;
            if (pdfBytes != null) {
                String invoiceNumber = event.getExtra() != null && event.getExtra().get("invoiceNumber") != null
                        ? String.valueOf(event.getExtra().get("invoiceNumber"))
                        : event.getInvoiceNumber();
                pdfFileName = invoiceNumber != null ? invoiceNumber + ".pdf" : "attachment.pdf";
            }

            NotificationRequest request = NotificationRequest.builder()
                    .channel(NotificationChannel.EMAIL)
                    .recipient(event.getRecipientEmail())
                    .templateName(def.getTemplateName())
                    .variables(variables)
                    .priority(NotificationPriority.NORMAL)
                    .tenantId(tenantId)
                    .pdfAttachment(pdfBytes)
                    .pdfFileName(pdfFileName)
                    .build();

            orchestrator.process(request);
        } catch (Exception ex) {
            log.error("Failed to process billing notification: type={}, recipient={}, error={}",
                    event.getEventType(), event.getRecipientEmail(), ex.getMessage(), ex);
        }
    }

    public void handleAuthEvent(AuthNotificationEvent event) {
        log.info("Processing auth notification: type={}, recipient={}", event.getEventType(), event.getRecipientEmail());
        try {
            EmailTemplateConfig.TemplateDefinition def = templateConfig.getTemplate(event.getEventType());
            Map<String, Object> variables = buildAuthVariables(event);

            NotificationRequest request = NotificationRequest.builder()
                    .channel(NotificationChannel.EMAIL)
                    .recipient(event.getRecipientEmail())
                    .templateName(def.getTemplateName())
                    .variables(variables)
                    .priority(NotificationPriority.NORMAL)
                    .tenantId(null)
                    .build();

            orchestrator.process(request);
        } catch (Exception ex) {
            log.error("Failed to process auth notification: type={}, recipient={}, error={}",
                    event.getEventType(), event.getRecipientEmail(), ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildBillingVariables(BillingEvent event) {
        Map<String, Object> variables = new HashMap<>();

        variables.put("frontendUrl", frontendUrl);
        variables.put("currentYear", java.time.Year.now().getValue());
        variables.put("companyName", properties.getFromName());

        variables.put("recipientName", event.getRecipientName());
        variables.put("tenantName", event.getTenantName());

        variables.put("planName", event.getPlanName());
        variables.put("oldPlanName", event.getOldPlanName());
        variables.put("planPrice", event.getPlanPrice());
        variables.put("billingCycle", event.getBillingCycle());
        variables.put("trialEndsAt", formatInstant(event.getTrialEndsAt()));
        variables.put("subscriptionEndsAt", formatInstant(event.getSubscriptionEndsAt()));
        variables.put("nextBillingDate", formatInstant(event.getNextBillingDate()));

        variables.put("invoiceNumber", event.getInvoiceNumber());
        variables.put("invoiceAmount", event.getInvoiceAmount());
        variables.put("invoiceDueDate", formatInstant(event.getInvoiceDueDate()));

        String currency = event.getCurrency() != null ? event.getCurrency() : "INR";
        variables.put("currency", currency);

        variables.put("paymentId", event.getPaymentId());
        variables.put("paymentAmount", event.getPaymentAmount());
        variables.put("paymentMethod", event.getPaymentMethod());
        variables.put("retryAttempt", event.getRetryAttempt());
        variables.put("maxRetries", event.getMaxRetries());

        variables.put("metricName", event.getMetricName());
        variables.put("currentUsage", event.getCurrentUsage());
        variables.put("usageLimit", event.getUsageLimit());
        variables.put("usagePercent", event.getUsagePercent());

        if (event.getExtra() != null) {
            event.getExtra().forEach((k, v) -> {
                if (!"pdfAttachment".equals(k)) {
                    variables.put(k, v);
                }
                if ("currency".equals(k) && v != null) {
                    variables.put("currency", v.toString());
                }
            });
        }

        return variables;
    }

    private Map<String, Object> buildAuthVariables(AuthNotificationEvent event) {
        Map<String, Object> variables = new HashMap<>();

        variables.put("frontendUrl", frontendUrl);
        variables.put("currentYear", java.time.Year.now().getValue());
        variables.put("companyName", properties.getFromName());

        variables.put("recipientName", event.getRecipientName());
        variables.put("tenantName", event.getTenantName());
        variables.put("ipAddress", event.getIpAddress());
        variables.put("deviceInfo", event.getDeviceInfo());
        variables.put("eventTime", formatInstant(event.getEventTime()));
        variables.put("resetLink", event.getResetLink());

        return variables;
    }

    private byte[] extractPdfBytes(BillingEvent event) {
        try {
            if (event.getPdfAttachmentBase64() != null && !event.getPdfAttachmentBase64().isEmpty()) {
                return java.util.Base64.getDecoder().decode(event.getPdfAttachmentBase64());
            }

            if (event.getExtra() != null && event.getExtra().containsKey("pdfAttachment")) {
                Object pdfData = event.getExtra().get("pdfAttachment");
                if (pdfData instanceof String) {
                    return java.util.Base64.getDecoder().decode((String) pdfData);
                } else if (pdfData instanceof byte[]) {
                    return (byte[]) pdfData;
                } else if (pdfData instanceof java.util.List) {
                    java.util.List<?> list = (java.util.List<?>) pdfData;
                    byte[] bytes = new byte[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        Object item = list.get(i);
                        if (item instanceof Number) {
                            bytes[i] = ((Number) item).byteValue();
                        }
                    }
                    return bytes;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract PDF attachment: {}", e.getMessage());
        }
        return null;
    }

    private String formatInstant(java.time.Instant instant) {
        if (instant == null) return null;
        return DATE_FORMATTER.format(instant);
    }

    private String resolveSubject(String subjectTemplate, BillingEvent event) {
        String invoiceNumber = event.getExtra() != null && event.getExtra().get("invoiceNumber") != null
                ? String.valueOf(event.getExtra().get("invoiceNumber"))
                : (event.getInvoiceNumber() != null ? event.getInvoiceNumber() : "");

        return subjectTemplate
                .replace("{{invoiceNumber}}", invoiceNumber)
                .replace("{{retryAttempt}}", event.getRetryAttempt() != null
                        ? event.getRetryAttempt().toString() : "")
                .replace("{{usagePercent}}", event.getUsagePercent() != null
                        ? event.getUsagePercent().toString() : "")
                .replace("{{metricName}}", event.getMetricName() != null
                        ? event.getMetricName() : "")
                .replace("{{tenantName}}", event.getTenantName() != null
                        ? event.getTenantName() : "");
    }
}