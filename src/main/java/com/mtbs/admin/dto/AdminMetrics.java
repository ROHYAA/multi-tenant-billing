package com.mtbs.admin.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMetrics {

    private long totalTenants;
    private long activeTenants;
    private long suspendedTenants;
    private Map<String, Long> tenantsByPlan;
    private BigDecimal totalRevenue;
    private BigDecimal monthlyRecurringRevenue;
    private long activeSubscriptions;
    private long trialingSubscriptions;
    private long pastDueSubscriptions;
    private long totalInvoices;
    private long paidInvoices;
    private long openInvoices;
    private BigDecimal totalPaymentsAmount;
    private long failedPayments;
}
