package com.mtbs.admin.service;

import com.mtbs.admin.dto.AdminTenantDetailResponse;
import com.mtbs.admin.dto.AdminTenantListResponse;
import com.mtbs.admin.dto.ApproveTenantRequest;
import com.mtbs.admin.dto.ChangeTenantStatusRequest;
import com.mtbs.auth.service.PermissionCacheService;
import com.mtbs.auth.service.SchemaCacheService;
import com.mtbs.tenant.dto.tenant.ShopResponse;
import com.mtbs.auth.dto.user.UserResponse;
import com.mtbs.tenant.entity.Shop;
import com.mtbs.tenant.mapper.ShopMapper;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.TenantException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.service.ShopService;
import com.mtbs.shared.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for system administrators to manage all tenants globally.
 * Operates on the public schema but uses JdbcTemplate/TenantContext proxy
 * to fetch granular data from specific tenant schemas.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTenantService {

    private final ShopService tenantService;
    private final AdminTenantScopedService adminTenantScopedService;
    private final JdbcTemplate jdbcTemplate;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ShopMapper tenantMapper;
    private final PermissionCacheService permissionCacheService;
    private final SchemaCacheService schemaCacheService;

    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<AdminTenantListResponse> getAllTenants(Status status, Pageable pageable) {
        log.info("Admin fetching all tenants. Filters -> status: {}", status);

        Page<Shop> tenants = status != null
                ? tenantService.getTenantsByStatus(status, pageable)
                : tenantService.getAllTenantsPaged(pageable);

        return tenants.map(this::mapToListResponse);
    }

    @Transactional(readOnly = true)
    public AdminTenantDetailResponse getTenantDetail(Long tenantId) {
        log.info("Admin fetching tenant details: {}", tenantId);
        Shop tenant = tenantService.getTenantById(tenantId);

        String schema = "\"" + tenant.getSchemaName() + "\"";

        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".users WHERE deleted = false", Long.class);

        return AdminTenantDetailResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .schemaName(tenant.getSchemaName())
                .status(tenant.getStatus())
                .userCount(userCount != null ? userCount : 0L)
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }

    @Transactional
    public ShopResponse changeTenantStatus(Long tenantId, ChangeTenantStatusRequest request) {
        log.info("Admin changing tenant status: {} to {}", tenantId, request.getStatus());

        tenantService.updateTenantStatus(tenantId, request.getStatus());
        Shop tenant = tenantService.getTenantById(tenantId);

        permissionCacheService.evictTenant(tenant.getSchemaName());

        eventPublisher.publishEvent(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenant.getId())
                .entityName(tenant.getName())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(tenant.getId())
                .contextTenantName(tenant.getName())
                .changesAfter(Map.of("status", request.getStatus().name()))
                .description("Admin changed tenant status to: " + request.getStatus())
                .module("ADMIN_TENANT_MANAGEMENT")
                .build());

        return tenantMapper.toResponse(tenant);
    }

    @Transactional
    public ShopResponse approveTenant(Long tenantId, ApproveTenantRequest request) {
        log.info("Admin approving tenant: {} plan={} expiresAt={}", tenantId, request.getPlanName(), request.getSubscriptionExpiresAt());

        Shop tenant = tenantService.approveTenant(tenantId, request.getPlanName(), request.getSubscriptionExpiresAt());

        schemaCacheService.evict(tenant.getId());
        permissionCacheService.evictTenant(tenant.getSchemaName());

        eventPublisher.publishEvent(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenant.getId())
                .entityName(tenant.getName())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(tenant.getId())
                .contextTenantName(tenant.getName())
                .changesAfter(Map.of("status", Status.ACTIVE.name(), "planName", request.getPlanName()))
                .description("Admin approved newly signed-up shop — plan: " + request.getPlanName())
                .module("ADMIN_TENANT_MANAGEMENT")
                .build());

        return tenantMapper.toResponse(tenant);
    }

    @Transactional
    public ShopResponse reactivateTenant(Long tenantId, ApproveTenantRequest request) {
        log.info("Admin reactivating tenant: {} plan={} expiresAt={}", tenantId, request.getPlanName(), request.getSubscriptionExpiresAt());

        Shop tenant = tenantService.adminReactivateTenant(tenantId, request.getPlanName(), request.getSubscriptionExpiresAt());

        schemaCacheService.evict(tenant.getId());
        permissionCacheService.evictTenant(tenant.getSchemaName());

        eventPublisher.publishEvent(AuditLogEvent.builder()
                .action(AuditAction.STATUS_CHANGE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenant.getId())
                .entityName(tenant.getName())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(tenant.getId())
                .contextTenantName(tenant.getName())
                .changesAfter(Map.of("status", Status.ACTIVE.name(), "planName", request.getPlanName()))
                .description("Admin reactivated suspended shop — plan: " + request.getPlanName())
                .module("ADMIN_TENANT_MANAGEMENT")
                .build());

        return tenantMapper.toResponse(tenant);
    }

    public Page<UserResponse> getTenantUsers(Long tenantId, Pageable pageable) {
        log.info("Admin fetching users for tenant: {}", tenantId);
        Shop tenant = tenantService.getTenantById(tenantId);

        TenantContext.setTenantId(tenant.getId());
        try {
            log.debug("AdminTenantService.getTenantUsers: switching to schema={}", TenantContext.getTenantId());
            return adminTenantScopedService.getUsersInTenant(pageable);
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    public void deleteTenant(Long tenantId) {
        log.info("Admin soft deleting tenant: {}", tenantId);
        Shop tenant = tenantService.getTenantById(tenantId);

        tenant.setDeleted(true);
        tenant.setDeletedAt(Instant.now());
        tenantService.saveTenant(tenant);

        eventPublisher.publishEvent(AuditLogEvent.builder()
                .action(AuditAction.DELETE)
                .entityType(AuditEntityType.TENANT)
                .entityId(tenant.getId())
                .entityName(tenant.getName())
                .whoUserId(SecurityUtils.getCurrentUserId())
                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                .whoUserName(SecurityUtils.getCurrentUserName())
                .whoRole(SecurityUtils.getCurrentRole())
                .contextTenantId(tenant.getId())
                .contextTenantName(tenant.getName())
                .description("Admin deleted tenant: " + tenant.getName())
                .module("ADMIN_TENANT_MANAGEMENT")
                .severity("WARN")
                .build());
    }

    private AdminTenantListResponse mapToListResponse(Shop tenant) {
        String schema = "\"" + tenant.getSchemaName() + "\"";
        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".users WHERE deleted = false", Long.class);

        return AdminTenantListResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .schemaName(tenant.getSchemaName())
                .status(tenant.getStatus())
                .planName(tenant.getPlanName())
                .subscriptionExpiresAt(tenant.getSubscriptionExpiresAt())
                .userCount(userCount != null ? userCount : 0L)
                .createdAt(tenant.getCreatedAt())
                .build();
    }
}