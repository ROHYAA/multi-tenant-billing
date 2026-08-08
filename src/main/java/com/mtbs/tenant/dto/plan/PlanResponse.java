package com.mtbs.tenant.dto.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlanResponse {

    private Long id;

    private String code;

    private String name;

    private String displayName;

    private String description;

    private Boolean isActive;

    private Boolean isPublic;

    private Integer sortOrder;

    private String badge;

    @Builder.Default
    private List<PlanPricingResponse> pricing = new ArrayList<>();

    @Builder.Default
    private List<PlanFeatureResponse> features = new ArrayList<>();

    @Builder.Default
    private List<PlanLimitResponse> limits = new ArrayList<>();

    private Instant createdAt;

    private Instant updatedAt;
}