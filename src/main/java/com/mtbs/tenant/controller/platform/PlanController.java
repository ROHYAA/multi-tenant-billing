package com.mtbs.tenant.controller.platform;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.tenant.dto.plan.CreatePlanRequest;
import com.mtbs.tenant.dto.plan.PlanResponse;
import com.mtbs.tenant.dto.plan.UpdatePlanRequest;
import com.mtbs.tenant.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for plan management endpoints.
 * 
 * Provides CRUD operations for plans with nested pricing, features, and limits.
 * All responses wrapped in ApiResponse<T> for consistent error handling.
 * No @Transactional on controller methods — handled at service level.
 */
@RestController
@RequestMapping("/api/${api.version}/plans")
@RequiredArgsConstructor
@Tag(name = "Plans", description = "Plan management endpoints")
public class PlanController {

    private final PlanService planService;

    /**
     * Get all public (active and public) plans.
     * No authentication required. Returns summary of available plans.
     * 
     * @return list of public plans with pricing, features, limits
     */
    @GetMapping
    @Operation(summary = "Get all public plans", 
               description = "Returns all active and public plans. No authentication required.")
    public ResponseEntity<ApiResponse<List<PlanResponse>>> getPublicPlans() {
        return ResponseEntity.ok(ApiResponse.success(planService.getAllPublicPlans()));
    }

    /**
     * Get a plan by ID with full details (pricing, features, limits).
     * No authentication required.
     * 
     * @param id the plan ID
     * @return plan details with all child collections
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get plan by ID", 
               description = "Returns a specific plan by ID with pricing, features, and limits. No authentication required.")
    public ResponseEntity<ApiResponse<PlanResponse>> getPlanById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(planService.getPlanByIdAsResponse(id)));
    }

    /**
     * Get all plans including inactive ones.
     * Admin only. Used for plan management.
     * 
     * @return all plans (active and inactive)
     */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @Operation(summary = "Get all plans including inactive", 
               description = "Admin endpoint to get all plans (active and inactive).")
    public ResponseEntity<ApiResponse<List<PlanResponse>>> getAllPlans() {
        return ResponseEntity.ok(ApiResponse.success(planService.getAllPlans()));
    }

    /**
     * Create a new plan with pricing, features, and limits.
     * Admin only.
     * 
     * @param request CreatePlanRequest with code, displayName, and nested collections
     * @return created plan as PlanResponse
     */
    @PostMapping
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @Operation(summary = "Create a new plan", 
               description = "Creates a new pricing plan with pricing rows, feature flags, and usage limits.")
    public ResponseEntity<ApiResponse<PlanResponse>> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        PlanResponse response = planService.createPlan(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, "Plan created successfully"));
    }

    /**
     * Update an existing plan.
     * Admin only. All fields are optional; only provided fields are updated.
     * Collections (pricing, features, limits) are replaced if provided.
     * 
     * @param id the plan ID
     * @param request UpdatePlanRequest with optional fields to update
     * @return updated plan as PlanResponse
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @Operation(summary = "Update a plan", 
               description = "Updates an existing plan. Only provided fields are updated. Collections are replaced if provided.")
    public ResponseEntity<ApiResponse<PlanResponse>> updatePlan(@PathVariable Long id,
                                                                  @Valid @RequestBody UpdatePlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success(planService.updatePlan(id, request)));
    }

    /**
     * Deactivate a plan (soft-delete).
     * Admin only.
     * 
     * @param id the plan ID to deactivate
     * @return success response
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @Operation(summary = "Deactivate a plan", 
               description = "Soft-deletes and deactivates a plan. The plan is marked as deleted but data is retained.")
    public ResponseEntity<ApiResponse<Void>> deactivatePlan(@PathVariable Long id) {
        planService.deactivatePlan(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Plan deactivated successfully"));
    }
}
