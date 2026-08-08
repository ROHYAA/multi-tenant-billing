package com.mtbs.tenant.dto.plan;

import com.mtbs.shared.enums.billing.UsageMetric;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request DTO for creating/updating plan limits.
 * Note: value must be null when unlimited=true.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePlanLimitRequest {

    @NotNull(message = "Metric is required")
    private UsageMetric metric;

    /**
     * Limit value. Must be null when unlimited=true.
     */
    private Long value;

    @Builder.Default
    private Boolean unlimited = false;
}
