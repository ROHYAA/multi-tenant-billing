package com.mtbs.auth.controller.auth;

import com.mtbs.auth.dto.auth.AuthResponse;
import com.mtbs.auth.dto.auth.ForgotPasswordRequest;
import com.mtbs.auth.dto.auth.LoginRequest;
import com.mtbs.auth.dto.auth.LogoutRequest;
import com.mtbs.auth.dto.auth.RefreshTokenRequest;
import com.mtbs.auth.dto.auth.ResetPasswordRequest;
import com.mtbs.auth.dto.auth.SignupRequest;
import com.mtbs.auth.dto.auth.TenantResolutionRequest;
import com.mtbs.auth.dto.auth.TenantResolutionResponse;
import com.mtbs.auth.dto.auth.UserProfileResponse;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.auth.service.AuthService;
import com.mtbs.auth.service.LoginRateLimiter;
import com.mtbs.auth.service.PasswordResetService;
import com.mtbs.auth.service.SignupService;
import com.mtbs.shared.util.CookieUtils;
import com.mtbs.shared.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Tenant signup, login, token management, and password reset")
public class AuthController {

    private final AuthService authService;
    private final SignupService signupService;
    private final PasswordResetService passwordResetService;
    private final CookieUtils cookieUtils;
    private final LoginRateLimiter loginRateLimiter;

    // ── POST /api/auth/signup ─────────────────────────────────────────────────

    @PostMapping("/signup")
    @Operation(
            summary = "Create account — Phase 0 of onboarding",
            description = "Creates a tenant account, provisions the PostgreSQL schema, creates the " +
                    "ROLE_OWNER user, and returns user/tenant info. Tokens are set via HttpOnly, Secure, SameSite=Lax cookies. " +
                    "Tenant status is PENDING_ONBOARDING. Frontend must redirect to /onboarding after this call."
    )
    public ResponseEntity<ApiResponse<AuthResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletResponse response) {

        AuthResponse result = signupService.signup(request, response);
        
        // Note: SignupService will have already set the HttpOnly cookies if tokens were generated.
        // Controller just needs to return the response without tokens.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result, "Account created. Please complete onboarding."));
    }

    // ── POST /api/auth/tenants ─────────────────────────────────────────────────

    @PostMapping("/tenants")
    @Operation(
            summary = "Resolve tenant workspaces for an email address",
            description = "Returns tenant slugs associated with this email. " +
                    "Used for two-step login UX. Always returns 200 to prevent email enumeration."
    )
    public ResponseEntity<ApiResponse<TenantResolutionResponse>> resolveTenantsForEmail(
            @Valid @RequestBody TenantResolutionRequest request) {

        var tenants = authService.resolveTenantsForEmail(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(
                TenantResolutionResponse.fromOptions(tenants),
                "Tenants resolved"));
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────────

    @PostMapping("/login")
    @Operation(
            summary = "Tenant user login",
            description = "Authenticates tenant user. Tokens are set via HttpOnly, Secure, SameSite=Lax cookies. " +
                    "No tokens returned in response body.")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String ip = getClientIp(httpRequest);

        // 1. Check if this IP is already locked out before touching the DB
        loginRateLimiter.checkBlocked(ip);

        AuthResponse result;
        try {
            result = authService.login(request, ip, httpRequest.getHeader("User-Agent"), response);
        } catch (Exception e) {
            // Credential failure or any auth error — record the attempt
            // NOTE: recordFailure() will itself throw 429 if threshold is hit
            loginRateLimiter.recordFailure(ip);
            throw e;  // re-throw the original exception (invalid credentials, etc.)
        }

        // 2. Login succeeded — clear failure counter for this IP
        loginRateLimiter.clearFailures(ip);
        return ResponseEntity.ok(ApiResponse.success(result, "Login successful"));
    }

    // ── POST /api/auth/refresh ────────────────────────────────────────────────

    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh access token",
            description = "Issues new access token using refresh token from cookie. " +
                    "Rotates refresh token. Tokens are set via HttpOnly, Secure, SameSite=Lax cookies. " +
                    "No tokens returned in response body."
    )
    public ResponseEntity<ApiResponse<AuthResponse>> refreshAccessToken(
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        if (request == null) {
            request = new RefreshTokenRequest();
        }

        AuthResponse result = authService.refreshAccessToken(request, httpRequest, response);
        return ResponseEntity.ok(ApiResponse.success(result, "Token refreshed successfully"));
    }

    // ── POST /api/auth/logout ─────────────────────────────────────────────────

    @PostMapping("/logout")
    @Operation(
            summary = "Logout",
            description = "Revokes refresh token and clears HttpOnly auth cookies."
    )
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestBody(required = false) LogoutRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String tokenToRevoke = null;

        // Try to extract refresh token from cookie (HttpOnly)
        String cookieToken = cookieUtils.extractRefreshToken(httpRequest).orElse(null);
        if (StringUtils.hasText(cookieToken)) {
            tokenToRevoke = cookieToken;
        } else if (request != null && StringUtils.hasText(request.getRefreshToken())) {
            // Fallback: try request body (API clients, not browser)
            tokenToRevoke = request.getRefreshToken();
        }

        if (tokenToRevoke != null) {
            String ip = getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");
            authService.logout(new LogoutRequest(tokenToRevoke), SecurityUtils.getCurrentTenantId(), ip, userAgent, httpRequest, response);
        } else {
            // Even if no token to revoke, still clear cookies
            // This handles edge case: user closed browser, or cookies already expired
            cookieUtils.clearAuthCookies(response);
        }

        return ResponseEntity.ok(ApiResponse.success(null, "Logged out successfully"));
    }

    // ── GET /api/auth/me ──────────────────────────────────────────────────────

    @GetMapping("/me")
    @Operation(
            summary = "Get current user profile",
            description = "Returns the authenticated user's profile details from the JWT context."
    )
    public ResponseEntity<ApiResponse<UserProfileResponse>> getCurrentUserProfile() {
        UserProfileResponse result = authService.getCurrentUserProfile(
                SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentTenantId());
        return ResponseEntity.ok(ApiResponse.success(result, "Profile fetched successfully"));
    }

    // ── POST /api/auth/forgot-password ────────────────────────────────────────

    @PostMapping("/forgot-password")
    @Operation(
            summary = "Request password reset",
            description = "Sends a password reset link to the user's email if the account exists. " +
                    "Always returns 200 OK — never reveals whether the email is registered. " +
                    "Token expires in 15 minutes. Public endpoint — no JWT required."
    )
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        passwordResetService.requestPasswordReset(request.getTenantSlug(), request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(null,
                "If that email is registered, a reset link has been sent."));
    }

    // ── POST /api/auth/reset-password ─────────────────────────────────────────

    @PostMapping("/reset-password")
    @Operation(
            summary = "Reset password using token",
            description = "Validates the reset token and updates the user's password. " +
                    "Token is single-use and expires in 15 minutes. " +
                    "Returns 400 if the token is invalid or expired. " +
                    "Public endpoint — no JWT required."
    )
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {

        String ip = getClientIp(httpRequest);
        passwordResetService.resetPassword(
                request.getTenantSlug(),
                request.getToken(),
                request.getNewPassword(),
                ip,
                httpRequest.getHeader("User-Agent"));

        return ResponseEntity.ok(ApiResponse.success(null, "Password reset successfully. Please log in."));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }
}