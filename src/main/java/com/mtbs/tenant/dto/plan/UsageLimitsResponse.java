package com.mtbs.tenant.dto.plan;

import com.mtbs.shared.enums.billing.UsageMetric;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for usage limits with current usage tracking.
 * Used by PlanLimitService.getLimits() to return current usage against limits.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageLimitsResponse {

    private Long planId;

    private String planCode;

    @Builder.Default
    private List<UsageMetricLimit> metrics = new ArrayList<>();

    /**
     * Inner class representing a single usage metric limit with current usage.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageMetricLimit {

        private UsageMetric metric;

        /**
         * The limit value. Null when unlimited=true.
         */
        private Long limitValue;

        /**
         * Whether this metric is unlimited.
         */
        private Boolean unlimited;

        /**
         * Current usage for this metric in the billing period.
         */
        private Long currentUsage;

        /**
         * Display-friendly limit string.
         * "Unlimited" if unlimited=true, otherwise the limitValue as string.
         */
        private String displayLimit;

        /**
         * Usage as a percentage of the limit.
         * 0.0 if unlimited. Otherwise (currentUsage * 100.0) / limitValue.
         */
        private Double usagePercent;
    }
}
