package com.mtbs.auth.service;

import com.mtbs.auth.dto.auth.*;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.exception.TenantException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.shared.util.CookieUtils;
import com.mtbs.shared.util.SecurityUtils;
import com.mtbs.tenant.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Tenant auth orchestrator — public schema routing only.
 *
 * Responsibilities:
 *  - Look up the Tenant row in public schema via slug
 *  - Enforce tenant-level status guards
 *  - Set TenantContext BEFORE delegating to TenantAuthService
 *  - Always clear TenantContext in a finally block
 *
 * NOT responsible for account creation (SignupService)
 * or password reset (PasswordResetService).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final TenantService tenantService;
    private final TenantAuthService tenantAuthService;
    private final SlugCacheService slugCacheService;
    private final SchemaCacheService schemaCacheService;
    private final SlugGeneratorService slugGeneratorService;
    private final CookieUtils cookieUtils;

    // ── Login ─────────────────────────────────────────────────────────────────

    public AuthResponse login(LoginRequest request, String ipAddress, String deviceInfo, HttpServletResponse response) {
        log.info("Login attempt for tenantSlug={}", request.getTenantSlug());

        // Step 1: Resolve tenantId from slug (Redis → DB)
        Long tenantId = slugCacheService.resolveTenantId(request.getTenantSlug());

        // Step 2: Load full tenant with plan for status check
        Tenant tenant = tenantService.getTenantByIdWithPlan(tenantId);

        // Hard-block suspended / deactivated tenants only
        if (tenant.getStatus() == Status.SUSPENDED || tenant.getStatus() == Status.INACTIVE) {
            throw TenantException.suspended(tenant.getId());
        }

        // PENDING_ONBOARDING tenants are allowed — they log back in to resume the wizard.
        // The JWT is valid; frontend checks GET /api/onboarding/status and redirects.

        // Step 3: Resolve schemaName (Redis → DB)
        String schemaName = schemaCacheService.resolveSchemaName(tenantId);

        // Step 4: Set TenantContext
        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(schemaName);

        // Step 5: Delegate to tenant-scoped auth service
        TokenPair tokenPair = TokenPair.builder().build();
        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(schemaName);
        try {
            AuthResponse result = tenantAuthService.loginInTenantSchema(
                    request, tenant, ipAddress, deviceInfo, tokenPair);
            
            // Set HttpOnly cookies if tokens were generated
            String accessToken = tokenPair.getAccessToken();
            String refreshToken = tokenPair.getRefreshToken();
            
            if (accessToken != null && refreshToken != null) {
                cookieUtils.addAuthCookies(response, accessToken, refreshToken);
                
                // Return response without tokens
                result = AuthResponse.builder()
                        .user(result.getUser())
                        .tenant(result.getTenant())
                        .session(result.getSession())
                        .flags(result.getFlags())
                        .build();
            }
            
            return result;
        } finally {
            TenantContext.clear();
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    public AuthResponse refreshAccessToken(RefreshTokenRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        log.info("Refreshing access token for tenantSlug={}", request.getTenantSlug());

        // Extract refresh token from cookie (request body is fallback)
        String cookieRefreshToken = cookieUtils.extractRefreshToken(httpRequest).orElse(null);
        if (cookieRefreshToken != null && request.getRefreshToken() == null) {
            request.setRefreshToken(cookieRefreshToken);
        }

        // Step 1: Resolve tenantId from slug
        Long tenantId = slugCacheService.resolveTenantId(request.getTenantSlug());

        // Step 2: Load tenant with plan for status check
        Tenant tenant = tenantService.getTenantByIdWithPlan(tenantId);

        // Allow refresh for ACTIVE and PENDING_ONBOARDING tenants
        if (tenant.getStatus() == Status.SUSPENDED || tenant.getStatus() == Status.INACTIVE) {
            throw TenantException.suspended(tenant.getId());
        }

        // Step 3: Resolve schemaName
        String schemaName = schemaCacheService.resolveSchemaName(tenantId);

        // Step 4: Set TenantContext
        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(schemaName);

        // Step 5: Delegate to tenant-scoped auth service
        TokenPair tokenPair = TokenPair.builder().build();
        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(schemaName);
        try {
            AuthResponse result = tenantAuthService.refreshInTenantSchema(request, tenant, tokenPair);
            
            // Set HttpOnly cookies for new tokens
            String accessToken = tokenPair.getAccessToken();
            String refreshToken = tokenPair.getRefreshToken();
            
            if (accessToken != null && refreshToken != null) {
                cookieUtils.addAuthCookies(response, accessToken, refreshToken);
                
                // Return response without tokens
                result = AuthResponse.builder()
                        .user(result.getUser())
                        .tenant(result.getTenant())
                        .session(result.getSession())
                        .flags(result.getFlags())
                        .build();
            }
            
            return result;
        } finally {
            TenantContext.clear();
        }
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    public void logout(LogoutRequest request, Long tenantId, String ipAddress, String userAgent, HttpServletRequest httpRequest, HttpServletResponse response) {
        log.info("Logout for tenantId={}", tenantId);

        // Try to extract refresh token from cookie if not in request body
        if ((request == null || request.getRefreshToken() == null) && httpRequest != null) {
            String cookieToken = cookieUtils.extractRefreshToken(httpRequest).orElse(null);
            if (cookieToken != null) {
                if (request == null) {
                    request = new LogoutRequest(cookieToken);
                } else {
                    request.setRefreshToken(cookieToken);
                }
            }
        }

        Tenant tenant = tenantService.getTenantByIdWithPlan(tenantId);

        Long userId = SecurityUtils.getCurrentUserId();
        String userEmail = SecurityUtils.getCurrentUserEmail();
        String userName = SecurityUtils.getCurrentUserName();
        String role = SecurityUtils.getCurrentRole();

        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(tenant.getSchemaName());
        try {
            tenantAuthService.logoutInTenantSchema(
                    request.getRefreshToken(), userId, userEmail, userName, role, 
                    tenant, ipAddress, userAgent);
            
            // Clear HttpOnly cookies
            cookieUtils.clearAuthCookies(response);
        } finally {
            TenantContext.clear();
        }
    }

    // ── Profile ───────────────────────────────────────────────────────────────

    public UserProfileResponse getCurrentUserProfile(Long userId, Long tenantId) {
        log.info("Fetching profile for userId={}, tenantId={}", userId, tenantId);

        Tenant tenant = tenantService.getTenantByIdWithPlan(tenantId);

        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(tenant.getSchemaName());
        try {
            return tenantAuthService.getCurrentUserProfile(userId, tenant);
        } finally {
            TenantContext.clear();
        }
    }

    // ── Tenant Resolution (for two-step login) ───────────────────────────────

    public List<SlugGeneratorService.TenantOption> resolveTenantsForEmail(String email) {
        return slugGeneratorService.resolveTenantsForEmail(email);
    }
}