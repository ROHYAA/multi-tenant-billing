package com.mtbs.tenant.dto.plan;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request DTO for updating an existing plan.
 * All fields are optional: null means "do not change", empty collections mean "do not change".
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePlanRequest {

    private String code;

    @Size(max = 100, message = "Display name must not exceed 100 characters")
    private String displayName;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    private Boolean isPublic;

    @Min(value = 0, message = "Sort order must be non-negative")
    private Integer sortOrder;

    private String badge;

    private Boolean isActive;

    /**
     * Pricing rows to update/replace. Null or empty means "do not change".
     * If provided, replaces existing pricing rows for this plan.
     */
    private List<CreatePlanPricingRequest> pricing;

    /**
     * Features to update/replace. Null or empty means "do not change".
     */
    private List<CreatePlanFeatureRequest> features;

    /**
     * Limits to update/replace. Null or empty means "do not change".
     */
    private List<CreatePlanLimitRequest> limits;
}