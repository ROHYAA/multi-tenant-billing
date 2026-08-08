package com.mtbs.shared.event.billing;

import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.DomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingEvent implements DomainEvent {

    private NotificationEvent eventType;

    @Override
    public String getEventType() {
        return eventType != null ? eventType.name() : null;
    }

    private Long tenantId;
    private String tenantName;
    private String recipientEmail;
    private String recipientName;

    private String ipAddress;
    private String deviceInfo;
    private Instant eventTime;

    private String planName;
    private String oldPlanName;
    private BigDecimal planPrice;
    private String billingCycle;
    private Instant trialEndsAt;
    private Instant subscriptionEndsAt;
    private Instant nextBillingDate;

    private String invoiceNumber;
    private BigDecimal invoiceAmount;
    private String currency;
    private Instant invoiceDueDate;

    private String paymentId;
    private BigDecimal paymentAmount;
    private String paymentMethod;
    private Integer retryAttempt;
    private Integer maxRetries;

    private String metricName;
    private Long currentUsage;
    private Long usageLimit;
    private Integer usagePercent;

    private Map<String, Object> extra;

    private String pdfAttachmentBase64;
}