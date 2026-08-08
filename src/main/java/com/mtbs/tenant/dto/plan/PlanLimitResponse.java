package com.mtbs.tenant.dto.plan;

import com.mtbs.shared.enums.billing.UsageMetric;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Response DTO for plan limits (one limit row per metric per plan).
 * Includes displayValue computed by mapper.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanLimitResponse {

    private Long id;

    private UsageMetric metric;

    private Long value;

    private Boolean unlimited;

    /**
     * Display-friendly string representation of the limit.
     * Computed by mapper using: unlimited=true → "Unlimited", 
     * value!=null → String.valueOf(value), else → "N/A"
     */
    private String displayValue;
}
