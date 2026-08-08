package com.mtbs.tenant.mapper;

import com.mtbs.tenant.dto.plan.*;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.entity.PlanFeature;
import com.mtbs.tenant.entity.PlanLimit;
import com.mtbs.tenant.entity.PlanPricing;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * MapStruct mapper for Plan-related entities and DTOs.
 * Handles conversion between:
 * - Plan ↔ PlanResponse
 * - PlanPricing ↔ PlanPricingResponse
 * - PlanFeature ↔ PlanFeatureResponse
 * - PlanLimit ↔ PlanLimitResponse
 * - CreatePlanRequest → Plan
 * - UpdatePlanRequest → Plan
 */
@Mapper(componentModel = "spring")
public interface PlanMapper {

    // ──────────────────────────────────────────────────────────────────────────
    // Plan ↔ PlanResponse
    // ──────────────────────────────────────────────────────────────────────────

    PlanResponse toResponse(Plan entity);

    List<PlanResponse> toResponseList(List<Plan> entities);

    // ──────────────────────────────────────────────────────────────────────────
    // PlanPricing ↔ PlanPricingResponse
    // ──────────────────────────────────────────────────────────────────────────

    PlanPricingResponse toPricingResponse(PlanPricing entity);

    List<PlanPricingResponse> toPricingResponseList(List<PlanPricing> entities);

    // ──────────────────────────────────────────────────────────────────────────
    // PlanFeature ↔ PlanFeatureResponse
    // ──────────────────────────────────────────────────────────────────────────

    PlanFeatureResponse toFeatureResponse(PlanFeature entity);

    List<PlanFeatureResponse> toFeatureResponseList(List<PlanFeature> entities);

    // ──────────────────────────────────────────────────────────────────────────
    // PlanLimit ↔ PlanLimitResponse (with computed displayValue)
    // ──────────────────────────────────────────────────────────────────────────

    @Mapping(
        target = "displayValue",
        expression = "java(planLimitToDisplayValue(entity))"
    )
    PlanLimitResponse toLimitResponse(PlanLimit entity);

    List<PlanLimitResponse> toLimitResponseList(List<PlanLimit> entities);

    // ──────────────────────────────────────────────────────────────────────────
    // CreatePlanRequest → Plan (ignoring collections)
    // ──────────────────────────────────────────────────────────────────────────

    @Mapping(target = "pricing", ignore = true)
    @Mapping(target = "features", ignore = true)
    @Mapping(target = "limits", ignore = true)
    @Mapping(target = "name", source = "code")
    Plan toEntity(CreatePlanRequest request);

    // ──────────────────────────────────────────────────────────────────────────
    // UpdatePlanRequest → Plan (ignoring collections, updating in place)
    // ──────────────────────────────────────────────────────────────────────────

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pricing", ignore = true)
    @Mapping(target = "features", ignore = true)
    @Mapping(target = "limits", ignore = true)
    void updateEntity(UpdatePlanRequest request, @MappingTarget Plan entity);

    // ──────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Computes the display value for a PlanLimit.
     * unlimited=true → "Unlimited"
     * value!=null → String.valueOf(value)
     * else → "N/A"
     */
    default String planLimitToDisplayValue(PlanLimit limit) {
        if (limit == null) {
            return "N/A";
        }
        if (Boolean.TRUE.equals(limit.getUnlimited()) || limit.getValue() == null) {
            return "Unlimited";
        }
        return String.valueOf(limit.getValue());
    }
}

