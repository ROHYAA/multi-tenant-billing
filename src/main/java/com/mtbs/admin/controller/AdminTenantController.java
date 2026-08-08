package com.mtbs.admin.controller;

import com.mtbs.admin.dto.AdminTenantDetailResponse;
import com.mtbs.admin.dto.AdminTenantListResponse;
import com.mtbs.admin.dto.ChangeTenantPlanRequest;
import com.mtbs.admin.dto.ChangeTenantStatusRequest;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.dto.common.PageResponse;
import com.mtbs.tenant.dto.tenant.TenantResponse;
import com.mtbs.auth.dto.user.UserResponse;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.admin.service.AdminTenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Admin — Tenants", description = "Platform admin operations on all tenants")
@SecurityRequirement(name = "bearerAuth")
public class AdminTenantController {

    private final AdminTenantService adminTenantService;

    // ── GET /api/admin/tenants ────────────────────────────────────────────────

    @GetMapping
    @Operation(
            summary = "List all tenants",
            description = "Returns a paginated list of all tenants. " +
                    "Optionally filter by status (ACTIVE, SUSPENDED, PENDING_ONBOARDING, etc.) " +
                    "and/or plan ID."
    )
    public ResponseEntity<ApiResponse<PageResponse<AdminTenantListResponse>>> getAllTenants(
            @Parameter(description = "Filter by tenant status")
            @RequestParam(required = false) Status status,

            @Parameter(description = "Filter by plan ID")
            @RequestParam(required = false) Long planId,

            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<AdminTenantListResponse> response = adminTenantService.getAllTenants(status, planId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "Tenants fetched successfully"));
    }

    // ── GET /api/admin/tenants/{id} ───────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(
            summary = "Get tenant detail",
            description = "Returns full tenant details including user count, " +
                    "and subscription status fetched via JdbcTemplate cross-schema queries."
    )
    public ResponseEntity<ApiResponse<AdminTenantDetailResponse>> getTenantDetail(
            @PathVariable Long id) {

        AdminTenantDetailResponse response = adminTenantService.getTenantDetail(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant detail fetched successfully"));
    }

    // ── PUT /api/admin/tenants/{id}/status ────────────────────────────────────

    @PutMapping("/{id}/status")
    @Operation(
            summary = "Change tenant status",
            description = "Updates a tenant's status (ACTIVE, SUSPENDED, INACTIVE). " +
                    "SUSPENDED tenants cannot log in or access any API endpoints."
    )
    public ResponseEntity<ApiResponse<TenantResponse>> changeTenantStatus(
            @PathVariable Long id,
            @Valid @RequestBody ChangeTenantStatusRequest request) {

        TenantResponse response = adminTenantService.changeTenantStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant status updated successfully"));
    }

    // ── PUT /api/admin/tenants/{id}/plan ──────────────────────────────────────

    @PutMapping("/{id}/plan")
    @Operation(
            summary = "Change tenant plan",
            description = "Overrides a tenant's plan directly from the admin panel. " +
                    "This does not create a subscription — use this for manual plan overrides only."
    )
    public ResponseEntity<ApiResponse<TenantResponse>> changeTenantPlan(
            @PathVariable Long id,
            @Valid @RequestBody ChangeTenantPlanRequest request) {

        TenantResponse response = adminTenantService.changeTenantPlan(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant plan updated successfully"));
    }

    // ── GET /api/admin/tenants/{id}/users ─────────────────────────────────────

    @GetMapping("/{id}/users")
    @Operation(
            summary = "List users in a tenant",
            description = "Returns a paginated list of users within a specific tenant's schema. " +
                    "Sets TenantContext to the target tenant before querying."
    )
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getTenantUsers(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<UserResponse> response = adminTenantService.getTenantUsers(id, pageable);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant users fetched successfully"));
    }

    // ── DELETE /api/admin/tenants/{id} ────────────────────────────────────────

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete a tenant",
            description = "Soft-deletes a tenant record. " +
                    "The tenant's PostgreSQL schema and data are NOT dropped — " +
                    "this only marks the tenant row as deleted in the public schema."
    )
    public ResponseEntity<ApiResponse<Void>> deleteTenant(@PathVariable Long id) {
        adminTenantService.deleteTenant(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Tenant deleted successfully"));
    }
}