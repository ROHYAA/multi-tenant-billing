package com.mtbs.tenant.service;

import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.tenant.dto.tenant.TenantResponse;
import com.mtbs.tenant.dto.tenant.TenantSchemaInfoResponse;
import com.mtbs.tenant.dto.tenant.TenantStatusResponse;
import com.mtbs.tenant.dto.tenant.UpdateTenantRequest;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.mapper.TenantMapper;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.exception.TenantException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.billing.repository.SubscriptionRepository;
import com.mtbs.tenant.repository.TenantRepository;
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
 * Handles self-management operations for the currently authenticated Tenant.
 * Operates primarily on the public schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

        private final TenantRepository tenantRepository;
        private final SubscriptionRepository subscriptionRepository;
        private final JdbcTemplate jdbcTemplate;
        private final OutboxEventPublisher outboxEventPublisher;
        private final TenantMapper tenantMapper;

        @Transactional(readOnly = true)
        public Tenant getTenantById(Long tenantId) {
                log.info("Fetching tenant details for id: {}", tenantId);
                return tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));
        }

        public TenantResponse getTenantByIdAsResponse(Long tenantId) {
                return tenantMapper.toResponse(this.getTenantByIdWithPlan(tenantId));
        }

        public String fetchTenantName() {
                return tenantRepository.findById(TenantContext.getTenantId())
                        .map(Tenant::getName)
                        .orElse("Unknown");
        }

        public String getTenantNameById(Long tenantId) {
                return tenantRepository.findById(tenantId)
                        .map(Tenant::getName)
                        .orElse("Unknown");
        }

        public Tenant getTenantByIdWithPlan(Long tenantId) {
                log.info("Fetching tenant details with plan for id: {}", tenantId);
                return tenantRepository.findByIdWithPlan(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));
        }

        @Transactional(readOnly = true)
        public Tenant findTenantBySlug(String slug) {
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
        public List<Tenant> getAllTenants() {
                return tenantRepository.findAll();
        }

        @Transactional
        public Tenant saveTenant(Tenant tenant) {
                return tenantRepository.save(tenant);
        }

        @Transactional
        public void updateTenantStatus(Long tenantId, Status status) {
                Tenant tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));
                tenant.setStatus(status);
                tenantRepository.save(tenant);
        }

        @Transactional
        public TenantResponse updateTenant(Long tenantId, UpdateTenantRequest request) {
                log.info("Updating tenant id: {}", tenantId);
                Tenant tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                tenant.setName(request.getName());
                tenant = tenantRepository.save(tenant);

                fireAuditEvent(AuditAction.UPDATE, tenant.getId(), tenant.getName(),
                        Map.of("name", request.getName()),
                        "Tenant updated");

                return tenantMapper.toResponse(tenant);
        }

        @Transactional(readOnly = true)
        public TenantSchemaInfoResponse getTenantSchemaInfo(Long tenantId) {
                log.info("Fetching schema info counts for tenant id: {}", tenantId);
                Tenant tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                String schema = "\"" + tenant.getSchemaName() + "\"";

                Long userCount = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM " + schema + ".users WHERE deleted = false", Long.class);

                Long roleCount = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM " + schema + ".roles WHERE deleted = false", Long.class);

                Long subscriptionCount = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM " + schema + ".subscriptions WHERE deleted = false", Long.class);

                return TenantSchemaInfoResponse.builder()
                                .schemaName(tenant.getSchemaName())
                                .userCount(userCount != null ? userCount : 0L)
                                .roleCount(roleCount != null ? roleCount : 0L)
                                .subscriptionCount(subscriptionCount != null ? subscriptionCount : 0L)
                                .createdAt(tenant.getCreatedAt())
                                .build();
        }

        public TenantStatusResponse getTenantStatus(Long tenantId) {
                log.info("Fetching operational status for tenant id: {}", tenantId);

                Tenant tenant = this.getTenantByIdWithPlan(tenantId);

                TenantStatusResponse.TenantStatusResponseBuilder builder = TenantStatusResponse.builder()
                                .tenantStatus(tenant.getStatus())
                                .planName(tenant.getPlan() != null ? tenant.getPlan().getDisplayName() : null)
                                .isSuspended(tenant.getStatus() != Status.ACTIVE);

                // Fetch active subscription from the tenant's schema
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());
                try {
                        log.debug("TenantService.getTenantStatus: schema={}", TenantContext.getTenantId());

                        // Find any subscription that is ACTIVE or TRIALING
                        subscriptionRepository
                                        .findFirstByStatusIn(
                                                        List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING))
                                        .ifPresentOrElse(
                                                        sub -> {
                                                                builder.subscriptionStatus(sub.getStatus());
                                                                builder.currentPeriodEnd(sub.getCurrentPeriodEnd());
                                                                builder.trialEndsAt(sub.getTrialEnd());
                                                        },
                                                        () -> builder.subscriptionStatus(SubscriptionStatus.UNPAID));
                } finally {
                        TenantContext.clear();
                }

                return builder.build();
        }

        @Transactional
        public void deactivateTenant(Long tenantId) {
                if (!"OWNER".equals(SecurityUtils.getCurrentRole())) {
                        throw ResourceException.accessDenied();
                }
                log.info("Deactivating tenant id: {}", tenantId);
                Tenant tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                tenant.setStatus(Status.INACTIVE);
                tenantRepository.save(tenant);

                fireAuditEvent(AuditAction.STATUS_CHANGE, tenant.getId(), tenant.getName(),
                        Map.of("status", Status.INACTIVE.name()),
                        "Tenant deactivated by owner");
        }

        @Transactional
        public void reactivateTenant(Long tenantId) {
                log.info("Reactivating tenant id: {}", tenantId);
                Tenant tenant = tenantRepository.findById(tenantId)
                                .orElseThrow(() -> TenantException.notFound(tenantId));

                tenant.setStatus(Status.ACTIVE);
                tenantRepository.save(tenant);

                fireAuditEvent(AuditAction.STATUS_CHANGE, tenant.getId(), tenant.getName(),
                        Map.of("status", Status.ACTIVE.name()),
                        "Tenant reactivated");
        }

        // ================== Admin Query Methods ==================

        @Transactional(readOnly = true)
        public Optional<Tenant> findTenantByIdOptional(Long tenantId) {
                return tenantRepository.findById(tenantId);
        }

        @Transactional(readOnly = true)
        public Page<Tenant> getTenantsByStatus(Status status, Pageable pageable) {
                return tenantRepository.findByStatus(status, pageable);
        }

        @Transactional(readOnly = true)
        public List<Tenant> getTenantsByPlanId(Long planId) {
                return tenantRepository.findByPlanIdAndDeletedFalse(planId);
        }

        @Transactional(readOnly = true)
        public List<Tenant> getTenantsByStatusList(Status status) {
                return tenantRepository.findAllByStatus(status);
        }

        @Transactional(readOnly = true)
        public List<Tenant> getTenantsByPlanIdAndStatus(Long planId, Status status) {
                List<Tenant> byPlan = tenantRepository.findByPlanIdAndDeletedFalse(planId);
                List<Tenant> byStatus = tenantRepository.findAllByStatus(status);
                byPlan.retainAll(byStatus);
                return byPlan;
        }

        @Transactional(readOnly = true)
        public Page<Tenant> getAllTenantsPaged(Pageable pageable) {
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
                                .build(), "Tenant", entityId);
                } catch (Exception e) {
                        log.warn("Failed to fire audit event: {}", e.getMessage());
                }
        }
}
