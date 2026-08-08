package com.mtbs.tenant.controller;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.util.SecurityUtils;
import com.mtbs.tenant.dto.tenant.TenantResponse;
import com.mtbs.tenant.dto.tenant.TenantSchemaInfoResponse;
import com.mtbs.tenant.dto.tenant.TenantStatusResponse;
import com.mtbs.tenant.dto.tenant.UpdateTenantRequest;
import com.mtbs.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/tenant")
@RequiredArgsConstructor
@Tag(name = "Tenant Management", description = "Endpoints for tenants to manage their own settings and view status")
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    @Operation(summary = "Get the current tenant's basic details")
    public ResponseEntity<ApiResponse<TenantResponse>> getTenant() {
        TenantResponse response = tenantService.getTenantByIdAsResponse(SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant details retrieved"));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Update the current tenant's details (e.g. name)")
    public ResponseEntity<ApiResponse<TenantResponse>> updateTenant(
            @Valid @RequestBody UpdateTenantRequest request) {
        TenantResponse response = tenantService.updateTenant(SecurityUtils.getCurrentTenantId(), request);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant updated successfully"));
    }

    @GetMapping("/schema")
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Get high level counts of records belonging to this tenant's schema")
    public ResponseEntity<ApiResponse<TenantSchemaInfoResponse>> getTenantSchemaInfo() {
        TenantSchemaInfoResponse response = tenantService.getTenantSchemaInfo(SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(response, "Schema info retrieved"));
    }

    @GetMapping("/status")
    @Operation(summary = "Check the live status of the tenant's operation and active plan/subscription")
    public ResponseEntity<ApiResponse<TenantStatusResponse>> getTenantStatus() {
        TenantStatusResponse response = tenantService.getTenantStatus(SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant status retrieved"));
    }

    @PostMapping("/deactivate")
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Deactivate the tenant to stop usage (Owner only)")
    public ResponseEntity<ApiResponse<Void>> deactivateTenant() {
        tenantService.deactivateTenant(SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(null, "Tenant deactivated successfully"));
    }

    @PostMapping("/reactivate")
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Reactivate the tenant to resume usage (Owner only)")
    public ResponseEntity<ApiResponse<Void>> reactivateTenant() {
        tenantService.reactivateTenant(SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(null, "Tenant reactivated successfully"));
    }
}
