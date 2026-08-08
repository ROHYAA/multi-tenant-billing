package com.mtbs.admin.controller;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.admin.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Admin — Users", description = "Platform admin cross-tenant user operations")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final AdminUserService adminUserService;

    // ── DELETE /api/admin/users/tenant/{tenantId}/user/{userId} ──────────────

    @DeleteMapping("/tenant/{tenantId}/user/{userId}")
    @Operation(
            summary = "Remove user from a tenant",
            description = "Soft-deletes a specific user from a tenant's schema. " +
                    "Sets TenantContext to the target tenant before performing the delete. " +
                    "Use GET /api/admin/tenants/{id}/users to list users before removing."
    )
    public ResponseEntity<ApiResponse<Void>> removeUserFromTenant(
            @PathVariable Long tenantId,
            @PathVariable Long userId) {

        adminUserService.removeUserFromTenant(tenantId, userId);
        return ResponseEntity.ok(ApiResponse.success(null, "User removed from tenant successfully"));
    }
}