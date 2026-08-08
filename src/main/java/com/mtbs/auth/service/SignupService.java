package com.mtbs.auth.service;

import com.mtbs.auth.dto.auth.AuthResponse;
import com.mtbs.auth.dto.auth.SignupRequest;
import com.mtbs.auth.dto.auth.TokenPair;
import com.mtbs.shared.util.CookieUtils;
import com.mtbs.tenant.entity.TenantOnboarding;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.tenant.enums.KycStatus;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.event.auth.AuthNotificationEvent;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.AuthException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.repository.TenantOnboardingRepository;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.tenant.service.TenantFlywayMigrationService;
import com.mtbs.tenant.service.PlanService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Handles Phase 0 of the onboarding flow — account creation only.
 *
 * Responsibilities:
 *  1. Validate email uniqueness across all tenant schemas (via public lookup)
 *  2. Create Tenant row in public schema with status=PENDING_ONBOARDING
 *  3. Provision PostgreSQL schema via Flyway
 *  4. Create ROLE_OWNER user in the new schema via TenantScopedAuthService
 *  5. Create TenantOnboarding record in public schema
 *  6. Fire USER_REGISTERED notification (welcome email)
 *  7. Return JWT — tenant can now navigate the app and resume onboarding
 *
 * NOT @Transactional at this level — public tenant save and tenant-schema
 * user creation must be in separate transaction boundaries (different schemas).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignupService {

    private final TenantService tenantService;
    private final TenantOnboardingRepository onboardingRepository;
    private final TenantFlywayMigrationService tenantFlywayMigrationService;
    private final TenantAuthService tenantScopedAuthService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final CookieUtils cookieUtils;
    private final PlanService planService;

    public AuthResponse signup(SignupRequest request, HttpServletResponse response) {
        log.info("New signup request for email={}", request.getEmail());

        // 1. Email uniqueness check in public schema lookup table
        if (tenantService.tenantOwnerEmailExists(request.getEmail())) {
            throw AuthException.emailAlreadyExists(request.getEmail());
        }

        // 2. Derive a provisional schema name from email prefix
        String provisionalSlug = deriveProvisionalSlug(request.getEmail());
        String schemaName = "schema_" + provisionalSlug + "_" + System.currentTimeMillis();

        // 3. Persist Tenant row in public schema
        Tenant tenant = saveTenant(request, schemaName, provisionalSlug);

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

        // 7. Create onboarding record in public schema (after JWT is issued)
        saveOnboardingRecord(tenant.getId());

        // 8. Fire welcome notification — async, never blocks signup
        fireWelcomeNotification(request, tenant);

        log.info("Signup complete for tenantId={}, schemaName={}", tenant.getId(), schemaName);
        return authResponse;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Transactional
    public Tenant saveTenant(SignupRequest request, String schemaName, String provisionalSlug) {
        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .schemaName(schemaName)
                .slug(provisionalSlug)
                .status(Status.PENDING_ONBOARDING)
                .onboardingStep(0)
                .ownerEmail(request.getEmail())
                .build();
        Tenant savedTenant = tenantService.saveTenant(tenant);

        // Skip audit event during signup - schema not yet created
        // Audit event can be fired after onboarding completes

        return savedTenant;
    }

    @Transactional
    public void saveOnboardingRecord(Long tenantId) {
        TenantOnboarding record = TenantOnboarding.builder()
                .tenantId(tenantId)
                .kycStatus(KycStatus.PENDING)
                .build();
        onboardingRepository.save(record);
    }

    @Transactional
    public void markTenantFailed(Long tenantId) {
        tenantService.updateTenantStatus(tenantId, Status.ONBOARDING_ABANDONED);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenantId)
                .entityName("Tenant")
                .changesBefore(Map.of("status", Status.PENDING_ONBOARDING.name()))
                .changesAfter(Map.of("status", Status.ONBOARDING_ABANDONED.name()))
                .description("Tenant onboarding failed")
                .module("TENANT_MANAGEMENT")
                .severity("WARN")
                .build(), "Tenant", tenantId);
    }

    /**
     * Fires USER_REGISTERED event which triggers the welcome email.
     * Uses AuthNotificationEvent — maps to auth/welcome.html template.
     * Never throws — failure is logged and ignored so signup always succeeds.
     */
    private void fireWelcomeNotification(SignupRequest request, Tenant tenant) {
        try {
            outboxEventPublisher.save(AuthNotificationEvent.builder()
                    .eventType(NotificationEvent.USER_REGISTERED)
                    .recipientEmail(request.getEmail())
                    .recipientName(request.getName())
                    .tenantName(tenant.getName())
                    .eventTime(Instant.now())
                    .build(), "Tenant", tenant.getId());
            log.debug("USER_REGISTERED notification fired for email={}", request.getEmail());
        } catch (Exception e) {
            log.warn("Failed to fire USER_REGISTERED notification for email={}: {}",
                    request.getEmail(), e.getMessage());
        }
    }

    private String deriveProvisionalSlug(String email) {
        String prefix = email.split("@")[0];
        return prefix.replaceAll("[^a-zA-Z0-9]", "")
                .toLowerCase()
                .substring(0, Math.min(prefix.length(), 20));
    }
}