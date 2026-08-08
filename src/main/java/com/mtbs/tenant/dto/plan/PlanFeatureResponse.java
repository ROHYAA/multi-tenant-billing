package com.mtbs.tenant.dto.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Response DTO for plan features (one feature row per feature per plan).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanFeatureResponse {

    private Long id;

    private String featureKey;

    private Boolean enabled;
}
