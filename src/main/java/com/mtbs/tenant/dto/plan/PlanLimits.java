package com.mtbs.tenant.dto.plan;

import com.mtbs.shared.enums.billing.UsageMetric;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Response DTO for plan limits. Used by PlanLimitService to return current limits.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanLimits {

    private Long planId;

    private String planCode;

    @Builder.Default
    private List<PlanLimitResponse> limits = new ArrayList<>();

    /**
     * Gets the limit for a specific metric.
     * @param metric the usage metric
     * @return Optional containing the limit if found
     */
    public Optional<PlanLimitResponse> getLimit(UsageMetric metric) {
        return limits.stream()
                .filter(l -> l.getMetric() == metric)
                .findFirst();
    }

    /**
     * Checks if a specific metric is unlimited.
     * If no limit row exists for the metric, treats as unlimited (returns true).
     * @param metric the usage metric
     * @return true if metric is unlimited, false if limited
     */
    public boolean isUnlimited(UsageMetric metric) {
        return getLimit(metric)
                .map(PlanLimitResponse::getUnlimited)
                .orElse(true);
    }
}