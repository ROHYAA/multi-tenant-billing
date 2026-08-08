package com.mtbs.tenant.controller;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.util.SecurityUtils;
import com.mtbs.tenant.dto.tenant.ShopResponse;
import com.mtbs.tenant.dto.tenant.ShopSchemaInfoResponse;
import com.mtbs.tenant.dto.tenant.ShopStatusResponse;
import com.mtbs.tenant.dto.tenant.UpdateShopRequest;
import com.mtbs.tenant.service.ShopService;
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
public class ShopController {

    private final ShopService tenantService;

    @GetMapping
    @Operation(summary = "Get the current tenant's basic details")
    public ResponseEntity<ApiResponse<ShopResponse>> getTenant() {
        ShopResponse response = tenantService.getTenantByIdAsResponse(SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant details retrieved"));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Update the current tenant's details (e.g. name)")
    public ResponseEntity<ApiResponse<ShopResponse>> updateTenant(
            @Valid @RequestBody UpdateShopRequest request) {
        ShopResponse response = tenantService.updateTenant(SecurityUtils.getCurrentTenantId(), request);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant updated successfully"));
    }

    @GetMapping("/schema")
    @PreAuthorize("hasAuthority('PERMISSION_TENANT_MANAGE')")
    @Operation(summary = "Get high level counts of records belonging to this tenant's schema")
    public ResponseEntity<ApiResponse<ShopSchemaInfoResponse>> getTenantSchemaInfo() {
        ShopSchemaInfoResponse response = tenantService.getTenantSchemaInfo(SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(response, "Schema info retrieved"));
    }

    @GetMapping("/status")
    @Operation(summary = "Check the live status of the tenant's operation and active plan/subscription")
    public ResponseEntity<ApiResponse<ShopStatusResponse>> getTenantStatus() {
        ShopStatusResponse response = tenantService.getTenantStatus(SecurityUtils.getCurrentTenantId());
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
