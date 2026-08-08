package com.mtbs.auth.controller.auth;

import com.mtbs.shared.util.SecurityUtils;
import com.mtbs.auth.dto.auth.AuthResponse;
import com.mtbs.auth.dto.auth.SuperAdminLoginRequest;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.auth.security.UserPrincipal;
import com.mtbs.auth.service.SuperAdminAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/${api.version}/admin/auth")
@RequiredArgsConstructor
@Tag(name = "Super Admin Auth", description = "Platform administrator authentication")
public class AdminAuthController {

    private final SuperAdminAuthService superAdminAuthService;

@PostMapping("/login")
    @Operation(
            summary = "Super admin login",
            description = "Platform admin authentication. Separate from tenant auth.")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody SuperAdminLoginRequest request,
            HttpServletResponse response) {
        AuthResponse result = superAdminAuthService.adminLogin(
                request.getEmail(), request.getPassword(), response);
        return ResponseEntity.ok(ApiResponse.success(result, "Platform admin login successful"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Platform admin refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshAdminToken(
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        AuthResponse result = superAdminAuthService.adminRefresh(httpRequest, response);
        return ResponseEntity.ok(ApiResponse.success(result, "Admin token refreshed successfully"));
    }

    @PostMapping("/logout")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @Operation(summary = "Platform admin logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse response) {
        superAdminAuthService.adminLogout(response);
        return ResponseEntity.ok(ApiResponse.success(null, "Admin logged out successfully"));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @Operation(summary = "Get platform admin profile from JWT context")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProfile() {
        UserPrincipal principal = SecurityUtils.getCurrentUserPrincipal();
        Map<String, Object> profile = Map.of(
                "userId", principal.getId(),
                "email", principal.getEmail(),
                "role", "SUPER_ADMIN");
        return ResponseEntity.ok(ApiResponse.success(profile, "Profile retrieved"));
    }
}
