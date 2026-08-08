package com.mtbs.billing.service;

import com.mtbs.billing.dto.TenantBillingDashboard;
import com.mtbs.billing.dto.UsageLimitsResponse;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.repository.InvoiceRepository;
import com.mtbs.billing.repository.PaymentRepository;
import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.business.invoice.repository.BusinessInvoiceRepository;
import com.mtbs.business.payment.repository.BusinessPaymentRepository;
import com.mtbs.business.payment.entity.BusinessPayment;
import com.mtbs.shared.enums.billing.AlertSeverity;
import com.mtbs.shared.enums.billing.AlertType;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.util.SecurityUtils;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantBillingDashboardService {

    private final SubscriptionService subscriptionService;
    private final PlanService planService;
    private final UsageService usageService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final BusinessInvoiceRepository businessInvoiceRepository;
    private final BusinessPaymentRepository businessPaymentRepository;

    @Cacheable(value = "dashboard", key = "'tenant:' + #root.targetClass.name + ':' + T(com.mtbs.shared.util.SecurityUtils).getCurrentTenantId() + ':' + #days")
    @Transactional(readOnly = true)
    public TenantBillingDashboard getDashboard(int days) {
        log.debug("Building tenant billing dashboard for {} days", days);

        Long tenantId = SecurityUtils.getCurrentTenantId();

        Subscription sub = subscriptionService.getCurrentSubscriptionEntity();
        TenantBillingDashboard.SubscriptionSummary subscriptionSummary = null;
        Plan plan = null;

        if (sub != null) {
            try {
                plan = planService.getPlanById(sub.getPlanId());
                String planName = plan != null ? plan.getDisplayName() : null;
                
                Integer daysRemaining = null;
                if (sub.getCurrentPeriodEnd() != null) {
                    daysRemaining = (int) ChronoUnit.DAYS.between(Instant.now(), sub.getCurrentPeriodEnd());
                    daysRemaining = daysRemaining > 0 ? daysRemaining : 0;
                }

                subscriptionSummary = TenantBillingDashboard.SubscriptionSummary.builder()
                        .subscriptionId(sub.getId())
                        .planName(planName)
                        .status(sub.getStatus())
                        .currentPeriodEnd(sub.getCurrentPeriodEnd())
                        .daysRemaining(daysRemaining)
                        .build();
            } catch (Exception e) {
                log.warn("Could not load subscription info for planId={}", sub.getPlanId());
            }
        }

        TenantBillingDashboard.CostSummary costSummary = buildCostSummary(sub, plan);

        TenantBillingDashboard.UpcomingBilling upcomingBilling = buildUpcomingBilling(sub, plan);

        UsageLimitsResponse usageLimits = usageService.getLimitsForCurrentPeriod(tenantId);
        TenantBillingDashboard.UsageTrendsData usageTrends = usageService.getUsageTrends(tenantId, days);

        List<TenantBillingDashboard.Alert> alerts = buildAlerts(usageLimits, 
                sub != null ? sub.getStatus() : null, 
                costSummary != null ? costSummary.getTrialRemainingDays() : null,
                plan != null ? plan.getDisplayName() : null);

        TenantBillingDashboard.InvoiceSummary recentInvoice = buildRecentInvoice(sub);

        TenantBillingDashboard.PaymentSummary paymentSummary = buildPaymentSummary(sub, plan);

        TenantBillingDashboard.RevenueSummary revenueSummary = buildRevenueSummary(sub, paymentSummary);

        TenantBillingDashboard.Features features = buildFeatures(plan);

        List<TenantBillingDashboard.Recommendation> recommendations = buildRecommendations(
                usageLimits, costSummary != null ? costSummary.getTrialRemainingDays() : null, plan);

        TenantBillingDashboard.TenantHealth tenantHealth = computeTenantHealth(usageLimits, 
                paymentSummary.getFailedPayments(),
                costSummary != null ? costSummary.getTrialRemainingDays() : null,
                sub != null ? sub.getStatus() : null);

        return TenantBillingDashboard.builder()
                .subscription(subscriptionSummary)
                .costSummary(costSummary)
                .usage(usageLimits)
                .usageTrends(usageTrends)
                .alerts(alerts)
                .upcomingBilling(upcomingBilling)
                .recentInvoice(recentInvoice)
                .paymentSummary(paymentSummary)
                .revenueSummary(revenueSummary)
                .features(features)
                .recommendations(recommendations)
                .tenantHealth(tenantHealth)
                .build();
    }

    private TenantBillingDashboard.CostSummary buildCostSummary(Subscription sub, Plan plan) {
        if (sub == null || plan == null) return null;

        Integer trialDaysRemaining = null;
        if (sub.getStatus() == SubscriptionStatus.TRIALING && sub.getTrialEnd() != null) {
            long days = ChronoUnit.DAYS.between(Instant.now(), sub.getTrialEnd());
            trialDaysRemaining = days > 0 ? (int) days : 0;
        }

        BigDecimal currentPeriodCost = sub.getStatus() == SubscriptionStatus.ACTIVE
                ? planService.getPriceMonthly(plan.getId()) : BigDecimal.ZERO;
        
        String currency = planService.getCurrencyForPlan(plan.getId());
        String currencySymbol = getCurrencySymbol(currency);

        return TenantBillingDashboard.CostSummary.builder()
                .planPrice(planService.getPriceMonthly(plan.getId()))
                .currency(currency)
                .currencySymbol(currencySymbol)
                .billingCycle(sub.getBillingCycle() != null ? sub.getBillingCycle().name() : null)
                .currentPeriodCost(currentPeriodCost)
                .nextInvoiceEstimate(planService.getPriceMonthly(plan.getId()))
                .trialRemainingDays(trialDaysRemaining)
                .build();
    }

    private TenantBillingDashboard.UpcomingBilling buildUpcomingBilling(Subscription sub, Plan plan) {
        if (sub == null || (sub.getStatus() != SubscriptionStatus.ACTIVE && sub.getStatus() != SubscriptionStatus.TRIALING)) {
            return null;
        }

        BigDecimal amount = plan != null ? planService.getPriceMonthly(plan.getId()) : BigDecimal.ZERO;
        return TenantBillingDashboard.UpcomingBilling.builder()
                .nextBillingDate(sub.getCurrentPeriodEnd())
                .estimatedAmount(amount)
                .currency(plan != null ? planService.getCurrencyForPlan(plan.getId()) : "INR")
                .autoChargeEnabled(true)
                .build();
    }

    private TenantBillingDashboard.InvoiceSummary buildRecentInvoice(Subscription sub) {
        try {
            List<BusinessInvoice> paidInvoices =
                    businessInvoiceRepository.findAllPaid();
            if (!paidInvoices.isEmpty()) {
                BusinessInvoice bi = paidInvoices.get(0);
                return TenantBillingDashboard.InvoiceSummary.builder()
                        .invoiceId(bi.getId())
                        .invoiceNumber(bi.getInvoiceNumber())
                        .amount(bi.getTotalAmount())
                        .currency(bi.getCurrency())
                        .status(bi.getStatus().name())
                        .dueDate(bi.getDueDate())
                        .paidAt(bi.getPaidAt())
                        .build();
            }
        } catch (Exception e) {
            log.warn("Could not load recent invoice from business tables: {}", e.getMessage());
        }
        return null;
    }

    private TenantBillingDashboard.PaymentSummary buildPaymentSummary(Subscription sub, Plan plan) {
        long total = 0;
        long succeeded = 0;
        long failed = 0;
        BigDecimal lifetimeValue = BigDecimal.ZERO;
        BigDecimal avgPaymentAmount = BigDecimal.ZERO;
        Instant lastPaymentDate = null;
        String lastPaymentStatus = null;
        String lastFailureReason = null;

        try {
            total = businessPaymentRepository.countAll();
            
            List<BusinessPayment> successfulPayments = businessPaymentRepository.findAllSuccessful();
            succeeded = successfulPayments.size();
            
            lifetimeValue = businessPaymentRepository.sumAllSuccessful();
            if (lifetimeValue == null) lifetimeValue = BigDecimal.ZERO;

            if (succeeded > 0) {
                avgPaymentAmount = lifetimeValue.divide(
                        BigDecimal.valueOf(succeeded), 2, RoundingMode.HALF_UP);
            }

            if (!successfulPayments.isEmpty()) {
                BusinessPayment lastPayment = successfulPayments.get(0);
                lastPaymentDate = lastPayment.getPaidAt();
                lastPaymentStatus = "SUCCEEDED";
            }
        } catch (Exception e) {
            log.warn("Could not build payment summary from business tables: {}", e.getMessage());
        }

        String currency = plan != null  ? planService.getCurrencyForPlan(plan.getId()) : "INR";
        String currencySymbol = getCurrencySymbol(currency);

        return TenantBillingDashboard.PaymentSummary.builder()
                .totalPayments(total)
                .successfulPayments(succeeded)
                .failedPayments(failed)
                .lifetimeValue(lifetimeValue)
                .avgPaymentAmount(avgPaymentAmount.setScale(2, RoundingMode.HALF_UP))
                .currency(currency)
                .currencySymbol(currencySymbol)
                .lastPaymentDate(lastPaymentDate)
                .lastPaymentStatus(lastPaymentStatus)
                .lastFailureReason(lastFailureReason)
                .build();
    }

    private TenantBillingDashboard.RevenueSummary buildRevenueSummary(Subscription sub, 
            TenantBillingDashboard.PaymentSummary paymentSummary) {
        BigDecimal currentPeriodRevenue = BigDecimal.ZERO;
        BigDecimal lifetimeRevenue = BigDecimal.ZERO;
        BigDecimal lastPaymentAmount = null;
        Instant lastPaymentDate = null;

        try {
            Instant periodStart = sub != null ? sub.getCurrentPeriodStart() : null;
            Instant periodEnd = sub != null ? sub.getCurrentPeriodEnd() : null;

            if (periodStart != null && periodEnd != null) {
                List<com.mtbs.business.invoice.entity.BusinessInvoice> periodInvoices = 
                        businessInvoiceRepository.findAllPaidBetween(periodStart, periodEnd);
                currentPeriodRevenue = periodInvoices.stream()
                        .map(com.mtbs.business.invoice.entity.BusinessInvoice::getTotalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            List<com.mtbs.business.invoice.entity.BusinessInvoice> allPaidInvoices = 
                    businessInvoiceRepository.findAllPaid();
            lifetimeRevenue = allPaidInvoices.stream()
                    .map(com.mtbs.business.invoice.entity.BusinessInvoice::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (!allPaidInvoices.isEmpty()) {
                com.mtbs.business.invoice.entity.BusinessInvoice lastInvoice = allPaidInvoices.get(0);
                if (lastInvoice.getPaidAt() != null) {
                    lastPaymentDate = lastInvoice.getPaidAt();
                    
                    List<BusinessPayment> lastInvoicePayments = 
                            businessPaymentRepository.findAllByInvoiceId(lastInvoice.getId());
                    if (!lastInvoicePayments.isEmpty()) {
                        lastPaymentAmount = lastInvoicePayments.get(0).getAmount();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not build revenue summary from business tables: {}", e.getMessage());
        }

        return TenantBillingDashboard.RevenueSummary.builder()
                .currentPeriodRevenue(currentPeriodRevenue)
                .lifetimeRevenue(lifetimeRevenue)
                .lastPaymentAmount(lastPaymentAmount)
                .lastPaymentDate(lastPaymentDate)
                .build();
    }

    private TenantBillingDashboard.Features buildFeatures(com.mtbs.tenant.entity.Plan plan) {
        if (plan == null) {
            return TenantBillingDashboard.Features.builder().build();
        }

        String planCode = plan.getCode().toUpperCase();
        boolean isProOrAbove = "PRO".equals(planCode) || "ENTERPRISE".equals(planCode);
        boolean isEnterprise = "ENTERPRISE".equals(planCode);

        boolean customRoles = isProOrAbove;
        boolean prioritySupport = isProOrAbove;
        boolean advancedAnalytics = isEnterprise;
        boolean customBranding = isEnterprise;
        boolean apiAccess = isProOrAbove;

        return TenantBillingDashboard.Features.builder()
                .customRoles(customRoles)
                .prioritySupport(prioritySupport)
                .advancedAnalytics(advancedAnalytics)
                .customBranding(customBranding)
                .apiAccess(apiAccess)
                .build();
    }

    private List<TenantBillingDashboard.Recommendation> buildRecommendations(UsageLimitsResponse usage, 
            Integer trialDaysRemaining, com.mtbs.tenant.entity.Plan plan) {
        List<TenantBillingDashboard.Recommendation> recommendations = new ArrayList<>();

        if (plan == null) return recommendations;

        String suggestedPlan = planService.getNextPlan(plan.getCode())
                .map(Plan::getCode)
                .orElse(null);

        if (trialDaysRemaining != null && trialDaysRemaining <= 14) {
            recommendations.add(TenantBillingDashboard.Recommendation.builder()
                    .type("TRIAL_CONVERSION")
                    .message("Upgrade before trial ends to avoid service interruption")
                    .suggestedPlan(suggestedPlan)
                    .build());
        }

        double maxUsagePercent = 0;
        if (usage != null) {
            if (usage.getApiCalls() != null) maxUsagePercent = Math.max(maxUsagePercent, usage.getApiCalls().getUsagePercent());
            if (usage.getUsers() != null) maxUsagePercent = Math.max(maxUsagePercent, usage.getUsers().getUsagePercent());
            if (usage.getStorage() != null) maxUsagePercent = Math.max(maxUsagePercent, usage.getStorage().getUsagePercent());
        }

        if (maxUsagePercent >= 80) {
            recommendations.add(TenantBillingDashboard.Recommendation.builder()
                    .type("UPGRADE")
                    .message("Your usage is approaching limits (" + String.format("%.0f", maxUsagePercent) + "%). Upgrade to " + suggestedPlan + " for higher limits.")
                    .suggestedPlan(suggestedPlan)
                    .build());
        }

        return recommendations;
    }

    private TenantBillingDashboard.TenantHealth computeTenantHealth(
            UsageLimitsResponse usage, long failedPayments,
            Integer trialDaysRemaining, SubscriptionStatus status) {
        
        int riskScore = 0;
        String usageSignal = "LOW";
        String billingSignal = "OK";
        String activitySignal = "LOW";
        
        if (usage != null) {
            double maxUsage = 0;
            if (usage.getApiCalls() != null) maxUsage = Math.max(maxUsage, usage.getApiCalls().getUsagePercent());
            if (usage.getUsers() != null) maxUsage = Math.max(maxUsage, usage.getUsers().getUsagePercent());
            if (usage.getStorage() != null) maxUsage = Math.max(maxUsage, usage.getStorage().getUsagePercent());
            
            if (maxUsage >= 90) {
                riskScore += 3;
                usageSignal = "HIGH";
            } else if (maxUsage >= 70) {
                riskScore += 1;
                usageSignal = "MEDIUM";
            }
            
            long apiUsed = usage.getApiCalls() != null ? usage.getApiCalls().getUsed() : 0;
            if (apiUsed < 10) {
                activitySignal = "LOW";
            } else if (apiUsed < 100) {
                activitySignal = "MEDIUM";
            } else {
                activitySignal = "HIGH";
            }
        }
        
        if (failedPayments >= 3) {
            riskScore += 3;
            billingSignal = "CRITICAL";
        } else if (failedPayments >= 2) {
            riskScore += 2;
            billingSignal = "WARNING";
        } else if (failedPayments >= 1) {
            billingSignal = "WARNING";
        }
        
        if (status == SubscriptionStatus.TRIALING && trialDaysRemaining != null) {
            if (trialDaysRemaining <= 3) {
                riskScore += 2;
                activitySignal = "HIGH";
            } else if (trialDaysRemaining <= 7) {
                riskScore += 1;
                activitySignal = "MEDIUM";
            } else if (trialDaysRemaining <= 14) {
                activitySignal = "MEDIUM";
            }
        } else if (status == SubscriptionStatus.ACTIVE && activitySignal.equals("LOW")) {
            activitySignal = "LOW";
        }
        
        String riskLevel, statusEnum;
        if (riskScore >= 5) {
            riskLevel = "HIGH";
            statusEnum = "CRITICAL";
        } else if (riskScore >= 2) {
            riskLevel = "MEDIUM";
            statusEnum = "WARNING";
        } else {
            riskLevel = "LOW";
            statusEnum = "HEALTHY";
        }
        
        return TenantBillingDashboard.TenantHealth.builder()
                .status(statusEnum)
                .riskLevel(riskLevel)
                .signals(TenantBillingDashboard.TenantHealth.HealthSignals.builder()
                        .usage(usageSignal)
                        .billing(billingSignal)
                        .activity(activitySignal)
                        .build())
                .build();
    }

    private List<TenantBillingDashboard.Alert> buildAlerts(UsageLimitsResponse usage,
            SubscriptionStatus status, Integer trialDaysRemaining, String planName) {
        List<TenantBillingDashboard.Alert> alerts = new ArrayList<>();

        if (planName != null) {
            alerts.add(createAlert("PLAN_INFO", "You are currently on " + planName, AlertSeverity.LOW));
        }

        if (usage == null) return alerts;

        if (usage.getApiCalls() != null) {
            double percent = usage.getApiCalls().getUsagePercent();
            if (percent >= 90) {
                alerts.add(createAlert(AlertType.USAGE_WARNING.name(), 
                        "API calls at " + formatPercent(percent) + "% of limit", AlertSeverity.HIGH));
            } else if (percent >= 70) {
                alerts.add(createAlert(AlertType.USAGE_WARNING.name(), 
                        "API calls at " + formatPercent(percent) + "% of limit", AlertSeverity.MEDIUM));
            }
        }

        if (usage.getUsers() != null) {
            double percent = usage.getUsers().getUsagePercent();
            if (percent >= 90) {
                alerts.add(createAlert(AlertType.USAGE_WARNING.name(), 
                        "User seats at " + formatPercent(percent) + "% of limit", AlertSeverity.HIGH));
            } else if (percent >= 70) {
                alerts.add(createAlert(AlertType.USAGE_WARNING.name(), 
                        "User seats at " + formatPercent(percent) + "% of limit", AlertSeverity.MEDIUM));
            }
        }

        if (usage.getStorage() != null) {
            double percent = usage.getStorage().getUsagePercent();
            if (percent >= 90) {
                alerts.add(createAlert(AlertType.USAGE_WARNING.name(), 
                        "Storage at " + formatPercent(percent) + "% of limit", AlertSeverity.HIGH));
            } else if (percent >= 70) {
                alerts.add(createAlert(AlertType.USAGE_WARNING.name(), 
                        "Storage at " + formatPercent(percent) + "% of limit", AlertSeverity.MEDIUM));
            }
        }

        if (status == SubscriptionStatus.TRIALING && trialDaysRemaining != null) {
            AlertSeverity severity = null;
            String message = null;
            
            if (trialDaysRemaining <= 3) {
                severity = AlertSeverity.HIGH;
                message = "Your trial ends in " + trialDaysRemaining + " days - upgrade now!";
            } else if (trialDaysRemaining <= 7) {
                severity = AlertSeverity.MEDIUM;
                message = "Your trial ends in " + trialDaysRemaining + " days";
            } else if (trialDaysRemaining <= 14) {
                severity = AlertSeverity.LOW;
                message = "Your trial ends in " + trialDaysRemaining + " days";
            }
            
            if (severity != null && message != null) {
                alerts.add(createAlert(AlertType.TRIAL_ENDING.name(), message, severity));
            }
        }

        return alerts;
    }

    private TenantBillingDashboard.Alert createAlert(String type, String message, AlertSeverity severity) {
        return TenantBillingDashboard.Alert.builder()
                .type(type)
                .message(message)
                .severity(severity.name())
                .build();
    }

    private String formatPercent(double percent) {
        return String.format("%.2f", percent);
    }
    
    private String getCurrencySymbol(String currency) {
        if (currency == null) return "₹";
        return switch (currency.toUpperCase()) {
            case "USD" -> "$";
            case "EUR" -> "€";
            case "GBP" -> "£";
            case "JPY" -> "¥";
            case "INR" -> "₹";
            default -> "₹";
        };
    }
}