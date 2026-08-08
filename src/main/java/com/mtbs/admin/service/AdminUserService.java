package com.mtbs.admin.service;

import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.auth.service.UserService;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Platform admin operations on users across tenant schemas.
 *
 * NOTE: listUsersInTenant() was removed — it was a duplicate of
 * AdminTenantService.getTenantUsers(). Use GET /api/admin/tenants/{id}/users
 * to list users in a tenant. That endpoint goes through AdminTenantService
 * which is the correct owner of cross-tenant user listing.
 *
 * This service retains only removeUserFromTenant() which is a distinct
 * destructive operation that belongs in its own admin user controller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private final TenantService tenantService;
    private final UserService userService;
    private final OutboxEventPublisher outboxEventPublisher;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Soft-deletes a user from a specific tenant schema.
     * Sets TenantContext to the target tenant before delegating to UserService.
     */
    public void removeUserFromTenant(Long tenantId, Long userId) {
        log.info("Admin removing userId={} from tenantId={}", userId, tenantId);

        Tenant tenant = tenantService.getTenantById(tenantId);

        TenantContext.setTenantId(tenant.getId());
        TenantContext.setCurrentSchema(tenant.getSchemaName());
        try {
            userService.deleteUser(userId);

            eventPublisher.publishEvent(AuditLogEvent.builder()
                    .action(AuditAction.DELETE)
                    .entityType(AuditEntityType.USER)
                    .entityId(userId)
                    .whoUserId(SecurityUtils.getCurrentUserId())
                    .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                    .whoUserName(SecurityUtils.getCurrentUserName())
                    .whoRole(SecurityUtils.getCurrentRole())
                    .contextTenantId(tenant.getId())
                    .contextTenantName(tenant.getName())
                    .description("Admin removed user with ID: " + userId + " from tenant: " + tenant.getName())
                    .module("ADMIN_USER_MANAGEMENT")
                    .severity("WARN")
                    .build());

            log.info("User removed — userId={}, tenantId={}", userId, tenantId);
        } finally {
            TenantContext.clear();
        }
    }
}