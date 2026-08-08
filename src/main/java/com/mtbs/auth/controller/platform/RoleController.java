package com.mtbs.auth.controller.platform;

import com.mtbs.auth.dto.role.*;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.auth.dto.permission.PermissionResponse;
import com.mtbs.auth.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/${api.version}/roles")
@RequiredArgsConstructor
@Tag(name = "Role Management", description = "Endpoints for managing custom roles and applying permissions")
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_VIEW')")
    @Operation(summary = "Get all roles in the current tenant")
    public ResponseEntity<ApiResponse<List<RoleResponse>>> getAllRoles() {
        List<RoleResponse> response = roleService.getAllRoles();
        return ResponseEntity.ok(ApiResponse.success(response, "Roles retrieved successfully"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_VIEW')")
    @Operation(summary = "Get detailed information about a specific role")
    public ResponseEntity<ApiResponse<RoleDetailResponse>> getRoleById(@PathVariable Long id) {
        RoleDetailResponse response = roleService.getRoleById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Role retrieved successfully"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
    @Operation(summary = "Create a new custom role")
    public ResponseEntity<ApiResponse<RoleResponse>> createRole(
            @Valid @RequestBody CreateRoleRequest request) {
        RoleResponse response = roleService.createRole(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Role created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
    @Operation(summary = "Update an existing custom role")
    public ResponseEntity<ApiResponse<RoleResponse>> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request) {
        RoleResponse response = roleService.updateRole(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Role updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
    @Operation(summary = "Delete a role if it is not a system role and has no users")
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Role deleted successfully"));
    }

    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_VIEW')")
    @Operation(summary = "Get all permissions currently assigned to a role")
    public ResponseEntity<ApiResponse<List<PermissionResponse>>> getPermissionsForRole(@PathVariable Long id) {
        List<PermissionResponse> response = roleService.getPermissionsForRole(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Role permissions retrieved successfully"));
    }

    @PostMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
    @Operation(summary = "Assign a single permission to a role")
    public ResponseEntity<ApiResponse<RoleDetailResponse>> assignPermissionToRole(
            @PathVariable Long id,
            @Valid @RequestBody AssignPermissionRequest request) {
        RoleDetailResponse response = roleService.assignPermissionToRole(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Permission assigned to role successfully"));
    }

    @DeleteMapping("/{id}/permissions/{permId}")
    @PreAuthorize("hasAuthority('PERMISSION_ROLE_MANAGE')")
    @Operation(summary = "Remove a single permission from a role")
    public ResponseEntity<ApiResponse<Void>> removePermissionFromRole(
            @PathVariable Long id,
            @PathVariable Long permId) {
        roleService.removePermissionFromRole(id, permId);
        return ResponseEntity.ok(ApiResponse.success(null, "Permission removed from role successfully"));
    }
}
