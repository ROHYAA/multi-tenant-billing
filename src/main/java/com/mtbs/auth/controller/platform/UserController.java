package com.mtbs.auth.controller.platform;

import com.mtbs.auth.dto.user.*;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.auth.service.UserService;
import com.mtbs.shared.dto.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

@RestController
@RequestMapping("/api/${api.version}/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "Endpoints for managing users within the active tenant")
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_USER_VIEW')")
    @Operation(summary = "List all active users in the tenant, paginated")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getAllUsers(Pageable pageable) {
        Page<UserResponse> response = userService.getAllUsers(pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "All User retrieved successfully"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_USER_VIEW')")
    @Operation(summary = "Get a specific user by ID in the current tenant")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable Long id) {
        UserResponse response = userService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "User retrieved successfully"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_USER_MANAGE')")
    @Operation(summary = "Create a new user within the current tenant")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "User created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_USER_MANAGE')")
    @Operation(summary = "Update a specific user's basic details (name, email)")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        UserResponse response = userService.updateUser(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "User updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_USER_DELETE')")
    @Operation(summary = "Soft delete a user from the tenant schema")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success(null, "User deleted successfully"));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasAuthority('PERMISSION_USER_MANAGE')")
    @Operation(summary = "Change a user's role")
    public ResponseEntity<ApiResponse<UserResponse>> changeUserRole(
            @PathVariable Long id,
            @Valid @RequestBody ChangeUserRoleRequest request) {
        UserResponse response = userService.changeUserRole(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "User role updated successfully"));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERMISSION_USER_MANAGE')")
    @Operation(summary = "Suspend or activate a user account")
    public ResponseEntity<ApiResponse<UserResponse>> changeUserStatus(
            @PathVariable Long id,
            @Valid @RequestBody ChangeUserStatusRequest request) {
        UserResponse response = userService.changeUserStatus(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "User status updated successfully"));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the complete profile details of the current authenticated user")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile() {
        UserResponse response = userService.getMyProfile();
        return ResponseEntity.ok(ApiResponse.success(response, "Own profile retrieved successfully"));
    }

    @PutMapping("/me")
    @Operation(summary = "Update own profile and/or change password")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @Valid @RequestBody UpdateOwnProfileRequest request) {
        UserResponse response = userService.updateMyProfile(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Profile updated successfully"));
    }
}
