package com.mtbs.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantBillingDashboard {

    private SubscriptionSummary subscription;
    private CostSummary costSummary;
    private UsageLimitsResponse usage;
    private UsageTrendsData usageTrends;
    private List<Alert> alerts;
    private UpcomingBilling upcomingBilling;
    private TenantHealth tenantHealth;
    private InvoiceSummary recentInvoice;
    private PaymentSummary paymentSummary;
    private RevenueSummary revenueSummary;
    private Features features;
    private List<Recommendation> recommendations;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionSummary {
        private Long subscriptionId;
        private String planName;
        private SubscriptionStatus status;
        private Instant currentPeriodEnd;
        private Integer daysRemaining;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostSummary {
        private BigDecimal planPrice;
        private String currency;
        private String currencySymbol;
        private String billingCycle;
        private BigDecimal currentPeriodCost;
        private BigDecimal nextInvoiceEstimate;
        private Integer trialRemainingDays;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceSummary {
        private Long invoiceId;
        private String invoiceNumber;
        private BigDecimal amount;
        private String currency;
        private String status;
        private Instant dueDate;
        private Instant paidAt;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentSummary {
        private long totalPayments;
        private long successfulPayments;
        private long failedPayments;
        private BigDecimal lifetimeValue;
        private BigDecimal avgPaymentAmount;
        private String currency;
        private String currencySymbol;
        private Instant lastPaymentDate;
        private String lastPaymentStatus;
        private String lastFailureReason;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevenueSummary {
        private BigDecimal currentPeriodRevenue;
        private BigDecimal lifetimeRevenue;
        private BigDecimal lastPaymentAmount;
        private Instant lastPaymentDate;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Alert {
        private String type;
        private String message;
        private String severity;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpcomingBilling {
        private Instant nextBillingDate;
        private BigDecimal estimatedAmount;
        private String currency;
        private boolean autoChargeEnabled;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantHealth {
        private String status;
        private String riskLevel;
        private HealthSignals signals;

        @Getter
        @Setter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class HealthSignals {
            private String usage;
            private String billing;
            private String activity;
        }
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageTrendsData {
        private List<UsageTrendPoint> apiCalls;
        private List<UsageTrendPoint> users;
        private List<StorageTrendPoint> storage;
        private int windowDays;

        @Getter
        @Setter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class UsageTrendPoint {
            private String date;
            private long count;
        }

        @Getter
        @Setter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class StorageTrendPoint {
            private String date;
            private double usedGb;
        }
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Features {
        private boolean customRoles;
        private boolean prioritySupport;
        private boolean advancedAnalytics;
        private boolean customBranding;
        private boolean apiAccess;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recommendation {
        private String type;
        private String message;
        private String suggestedPlan;
    }
}