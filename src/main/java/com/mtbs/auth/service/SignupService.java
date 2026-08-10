package com.mtbs.auth.service;

import com.mtbs.auth.dto.auth.AuthResponse;
import com.mtbs.auth.dto.auth.SignupRequest;
import com.mtbs.auth.dto.auth.TokenPair;
import com.mtbs.shared.util.CookieUtils;
import com.mtbs.tenant.entity.Shop;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.event.auth.AuthNotificationEvent;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.AuthException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.service.ShopService;
import com.mtbs.tenant.service.TenantFlywayMigrationService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Handles shop signup — account creation only.
 *
 * Responsibilities:
 *  1. Validate email uniqueness across all shop schemas (via public lookup)
 *  2. Create Shop row in public schema, ACTIVE immediately (no onboarding wizard)
 *  3. Provision PostgreSQL schema via Flyway
 *  4. Create ROLE_OWNER user in the new schema via TenantAuthService
 *  5. Fire USER_REGISTERED notification (welcome email)
 *  6. Return JWT — the shop can use the app immediately
 *
 * NOT @Transactional at this level — public tenant save and tenant-schema
 * user creation must be in separate transaction boundaries (different schemas).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignupService {

    private final ShopService tenantService;
    private final TenantFlywayMigrationService tenantFlywayMigrationService;
    private final TenantAuthService tenantScopedAuthService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final CookieUtils cookieUtils;

    public AuthResponse signup(SignupRequest request, HttpServletResponse response) {
        log.info("New signup request for email={}", request.getEmail());

        // 1. Email uniqueness check in public schema lookup table
        if (tenantService.tenantOwnerEmailExists(request.getEmail())) {
            throw AuthException.emailAlreadyExists(request.getEmail());
        }

        // 2. Derive a provisional schema name from email prefix
        String provisionalSlug = deriveProvisionalSlug(request.getEmail());
        String schemaName = "schema_" + provisionalSlug + "_" + System.currentTimeMillis();

        // 3. Persist Shop row in public schema, ACTIVE immediately
        Shop tenant = saveTenant(request, schemaName, provisionalSlug);

        // 4. Provision schema + run all tenant Flyway migrations
        try {
            tenantFlywayMigrationService.createSchemaAndMigrate(schemaName);
        } catch (Exception e) {
            log.error("Schema provisioning failed for schemaName={}", schemaName, e);
            markTenantFailed(tenant.getId());
            throw e;
        }

        // 5. Set TenantContext BEFORE any @Transactional in TenantScopedAuthService
        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(tenant.getSchemaName());

        // 6. Create ROLE_OWNER user in the tenant schema and return JWT
        TokenPair tokenPair = TokenPair.builder().build();
        AuthResponse authResponse = tenantScopedAuthService.createOwnerUserForSignup(request, tenant, tokenPair);

        // Set HttpOnly cookies
        if (tokenPair.getAccessToken() != null && tokenPair.getRefreshToken() != null) {
            cookieUtils.addAuthCookies(response, tokenPair.getAccessToken(), tokenPair.getRefreshToken());
        }

        // 7. Fire welcome notification — async, never blocks signup
        fireWelcomeNotification(request, tenant);

        log.info("Signup complete for tenantId={}, schemaName={}", tenant.getId(), schemaName);
        return authResponse;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Transactional
    public Shop saveTenant(SignupRequest request, String schemaName, String provisionalSlug) {
        Shop tenant = Shop.builder()
                .name(request.getName())
                .schemaName(schemaName)
                .slug(provisionalSlug)
                .status(Status.ACTIVE)
                .ownerEmail(request.getEmail())
                .build();
        return tenantService.saveTenant(tenant);
    }

    @Transactional
    public void markTenantFailed(Long tenantId) {
        tenantService.updateTenantStatus(tenantId, Status.INACTIVE);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenantId)
                .entityName("Shop")
                .changesBefore(Map.of("status", Status.ACTIVE.name()))
                .changesAfter(Map.of("status", Status.INACTIVE.name()))
                .description("Shop schema provisioning failed")
                .module("TENANT_MANAGEMENT")
                .severity("WARN")
                .build(), "Shop", tenantId);
    }

    /**
     * Fires USER_REGISTERED event which triggers the welcome email.
     * Uses AuthNotificationEvent — maps to auth/welcome.html template.
     * Never throws — failure is logged and ignored so signup always succeeds.
     */
    private void fireWelcomeNotification(SignupRequest request, Shop tenant) {
        try {
            outboxEventPublisher.save(AuthNotificationEvent.builder()
                    .eventType(NotificationEvent.USER_REGISTERED)
                    .recipientEmail(request.getEmail())
                    .recipientName(request.getName())
                    .tenantName(tenant.getName())
                    .eventTime(Instant.now())
                    .build(), "Shop", tenant.getId());
            log.debug("USER_REGISTERED notification fired for email={}", request.getEmail());
        } catch (Exception e) {
            log.warn("Failed to fire USER_REGISTERED notification for email={}: {}",
                    request.getEmail(), e.getMessage());
        }
    }

    private String deriveProvisionalSlug(String email) {
        String cleaned = email.split("@")[0]
                .replaceAll("[^a-zA-Z0-9]", "")
                .toLowerCase();
        return cleaned.substring(0, Math.min(cleaned.length(), 20));
    }
}
