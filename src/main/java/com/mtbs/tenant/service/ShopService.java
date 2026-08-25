package com.mtbs.tenant.service;

import com.mtbs.shared.event.outbox.OutboxEventPublisher;
import com.mtbs.tenant.dto.tenant.ShopResponse;
import com.mtbs.tenant.dto.tenant.ShopSchemaInfoResponse;
import com.mtbs.tenant.dto.tenant.ShopStatusResponse;
import com.mtbs.tenant.dto.tenant.UpdateShopRequest;
import com.mtbs.tenant.entity.Shop;
import com.mtbs.tenant.mapper.ShopMapper;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.exception.TenantException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.repository.ShopRepository;
import com.mtbs.shared.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Handles self-management operations for the currently authenticated Shop.
 * Operates primarily on the public schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopService {

        private final ShopRepository tenantRepository;
        private final JdbcTemplate jdbcTemplate;
        private final OutboxEventPublisher outboxEventPublisher;
        private final ShopMapper tenantMapper;

        @Transactional(readOnly = true)
        public Shop getTenantById(Long tenantId) {
                log.info("Fetching tenant details for id: {}", tenantId);
                return tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));
        }

        public ShopResponse getTenantByIdAsResponse(Long tenantId) {
                return tenantMapper.toResponse(this.getTenantById(tenantId));
        }

        public String fetchTenantName() {
                return tenantRepository.findById(TenantContext.getTenantId())
                        .map(Shop::getName)
                        .orElse("Unknown");
        }

        public String getTenantNameById(Long tenantId) {
                return tenantRepository.findById(tenantId)
                        .map(Shop::getName)
                        .orElse("Unknown");
        }

        @Transactional(readOnly = true)
        public Shop findTenantBySlug(String slug) {
                log.info("Fetching tenant by slug: {}", slug);
                return tenantRepository.findBySlug(slug.toLowerCase().trim())
                                .orElseThrow(() -> TenantException.notFound("No tenant found with identifier: " + slug));
        }

        @Transactional(readOnly = true)
        public boolean tenantOwnerEmailExists(String email) {
                return tenantRepository.existsByOwnerEmail(email);
        }

        @Transactional(readOnly = true)
        public boolean tenantSlugExists(String slug) {
                return tenantRepository.existsBySlug(slug.toLowerCase().trim());
        }

        @Transactional(readOnly = true)
        public List<Shop> getAllTenants() {
                return tenantRepository.findAll();
        }

        @Transactional
        public Shop saveTenant(Shop tenant) {
                return tenantRepository.save(tenant);
        }

        @Transactional
        public void updateTenantStatus(Long tenantId, Status status) {
                Shop tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));
                tenant.setStatus(status);
                tenantRepository.save(tenant);
        }

        @Transactional
        public ShopResponse updateTenant(Long tenantId, UpdateShopRequest request) {
                log.info("Updating tenant id: {}", tenantId);
                Shop tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                tenant.setName(request.getName());
                tenant = tenantRepository.save(tenant);

                fireAuditEvent(AuditAction.UPDATE, tenant.getId(), tenant.getName(),
                        Map.of("name", request.getName()),
                        "Shop updated");

                return tenantMapper.toResponse(tenant);
        }

        @Transactional(readOnly = true)
        public ShopSchemaInfoResponse getTenantSchemaInfo(Long tenantId) {
                log.info("Fetching schema info counts for tenant id: {}", tenantId);
                Shop tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                String schema = "\"" + tenant.getSchemaName() + "\"";

                Long userCount = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM " + schema + ".users WHERE deleted = false", Long.class);

                Long roleCount = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM " + schema + ".roles WHERE deleted = false", Long.class);

                return ShopSchemaInfoResponse.builder()
                                .schemaName(tenant.getSchemaName())
                                .userCount(userCount != null ? userCount : 0L)
                                .roleCount(roleCount != null ? roleCount : 0L)
                                .createdAt(tenant.getCreatedAt())
                                .build();
        }

        public ShopStatusResponse getTenantStatus(Long tenantId) {
                log.info("Fetching operational status for tenant id: {}", tenantId);

                Shop tenant = this.getTenantById(tenantId);

                return ShopStatusResponse.builder()
                                .tenantStatus(tenant.getStatus())
                                .isSuspended(tenant.getStatus() != Status.ACTIVE)
                                .build();
        }

        @Transactional
        public void deactivateTenant(Long tenantId) {
                if (!"OWNER".equals(SecurityUtils.getCurrentRole())) {
                        throw ResourceException.accessDenied();
                }
                log.info("Deactivating tenant id: {}", tenantId);
                Shop tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                tenant.setStatus(Status.INACTIVE);
                tenantRepository.save(tenant);

                fireAuditEvent(AuditAction.STATUS_CHANGE, tenant.getId(), tenant.getName(),
                        Map.of("status", Status.INACTIVE.name()),
                        "Shop deactivated by owner");
        }

        /**
         * SUPER_ADMIN-only: one-time PENDING_APPROVAL -> ACTIVE transition for a newly
         * self-signed-up shop. No audit event fired here (unlike deactivateTenant/
         * reactivateTenant) — this runs from admin context with no active tenant schema,
         * and fireAuditEvent's outbox write is tenant-schema-scoped; AdminTenantService
         * fires the audit event itself instead, the same way changeTenantStatus does.
         */
        @Transactional
        public Shop approveTenant(Long tenantId) {
                log.info("Approving tenant id: {}", tenantId);
                Shop tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                if (tenant.getStatus() != Status.PENDING_APPROVAL) {
                        throw TenantException.notPendingApproval(tenantId);
                }

                tenant.setStatus(Status.ACTIVE);
                return tenantRepository.save(tenant);
        }

        @Transactional
        public void reactivateTenant(Long tenantId) {
                log.info("Reactivating tenant id: {}", tenantId);
                Shop tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                tenant.setStatus(Status.ACTIVE);
                tenantRepository.save(tenant);

                fireAuditEvent(AuditAction.STATUS_CHANGE, tenant.getId(), tenant.getName(),
                        Map.of("status", Status.ACTIVE.name()),
                        "Shop reactivated");
        }

        // ================== Admin Query Methods ==================

        @Transactional(readOnly = true)
        public Optional<Shop> findTenantByIdOptional(Long tenantId) {
                return tenantRepository.findById(tenantId);
        }

        @Transactional(readOnly = true)
        public Page<Shop> getTenantsByStatus(Status status, Pageable pageable) {
                return tenantRepository.findByStatus(status, pageable);
        }

        @Transactional(readOnly = true)
        public List<Shop> getTenantsByStatusList(Status status) {
                return tenantRepository.findAllByStatus(status);
        }

        @Transactional(readOnly = true)
        public Page<Shop> getAllTenantsPaged(Pageable pageable) {
                return tenantRepository.findAll(pageable);
        }

        @Transactional(readOnly = true)
        public long getTotalTenantCount() {
                return tenantRepository.count();
        }

        private void fireAuditEvent(AuditAction action, Long entityId, String entityName,
                                   Map<String, Object> changes, String description) {
                try {
                        outboxEventPublisher.save(AuditLogEvent.builder()
                                .action(action)
                                .entityType(AuditEntityType.TENANT)
                                .entityId(entityId)
                                .entityName(entityName)
                                .whoUserId(SecurityUtils.getCurrentUserId())
                                .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                                .whoUserName(SecurityUtils.getCurrentUserName())
                                .whoRole(SecurityUtils.getCurrentRole())
                                .contextTenantId(TenantContext.getTenantId())
                                .contextTenantName(entityName)
                                .changesAfter(changes)
                                .description(description)
                                .module("TENANT_MANAGEMENT")
                                .build(), "Shop", entityId);
                } catch (Exception e) {
                        log.warn("Failed to fire audit event: {}", e.getMessage());
                }
        }
}
