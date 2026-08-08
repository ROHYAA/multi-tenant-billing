package com.mtbs.tenant.dto.plan;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request DTO for creating/updating plan features.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePlanFeatureRequest {

    @NotBlank(message = "Feature key is required")
    private String featureKey;

    @Builder.Default
    private Boolean enabled = true;
}
