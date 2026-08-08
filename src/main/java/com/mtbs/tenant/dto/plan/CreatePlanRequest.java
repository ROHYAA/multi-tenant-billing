package com.mtbs.tenant.dto.plan;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Request DTO for creating a new plan.
 * Includes nested pricing, features, and limits.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePlanRequest {

    @NotBlank(message = "Plan code is required")
    @Size(max = 50, message = "Plan code must not exceed 50 characters")
    private String code;

    @NotBlank(message = "Display name is required")
    @Size(max = 100, message = "Display name must not exceed 100 characters")
    private String displayName;

    private String description;

    @Builder.Default
    private Boolean isPublic = true;

    @Builder.Default
    @Min(value = 0, message = "Sort order must be non-negative")
    private Integer sortOrder = 0;

    private String badge;

    @NotEmpty(message = "At least one pricing is required")
    private List<CreatePlanPricingRequest> pricing;

    @Builder.Default
    private List<CreatePlanFeatureRequest> features = new ArrayList<>();

    @Builder.Default
    private List<CreatePlanLimitRequest> limits = new ArrayList<>();
}