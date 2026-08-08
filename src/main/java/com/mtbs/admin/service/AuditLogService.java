package com.mtbs.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtbs.admin.dto.AuditLogResponse;
import com.mtbs.admin.mapper.AdminMapper;
import com.mtbs.admin.repository.AuditLogRepository;
import com.mtbs.admin.entity.AuditLog;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.shared.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AdminMapper adminMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.audit.retention-days:365}")
    private int retentionDays;

    @Value("${app.audit.enabled:true}")
    private boolean auditEnabled;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditAction whatAction, AuditEntityType whereEntityType, Long whereEntityId,
                    String whereEntityName, Object beforeState, Object afterState,
                    String description, String module, Map<String, Object> metadata) {
        if (!auditEnabled) {
            log.debug("Audit logging is disabled");
            return;
        }

        try {
            AuditLog auditLog = AuditLog.builder()
                    .whoUserId(SecurityUtils.getCurrentUserId())
                    .whoUserEmail(SecurityUtils.getCurrentUserEmail())
                    .whoUserName(SecurityUtils.getCurrentUserName())
                    .whoRole(SecurityUtils.getCurrentRole())
                    .whatAction(whatAction)
                    .whereEntityType(whereEntityType)
                    .whereEntityId(whereEntityId)
                    .whereEntityName(whereEntityName)
                    .changesBefore(toMap(beforeState))
                    .changesAfter(toMap(afterState))
                    .changesSummary(generateChangesSummary(beforeState, afterState))
                    .contextTenantId(TenantContext.getTenantId())
                    .contextTenantName(TenantContext.getSchemaName())
                    .contextIpAddress(SecurityUtils.getClientIpAddress())
                    .contextUserAgent(SecurityUtils.getUserAgent())
                    .contextMetadata(metadata)
                    .description(description)
                    .module(module)
                    .severity(determineSeverity(whatAction))
                    .build();

            auditLogRepository.save(auditLog);
            log.debug("Audit log saved: action={}, entity={}:{}",
                    whatAction, whereEntityType, whereEntityId);
        } catch (Exception e) {
            log.error("Failed to save audit log: action={}, entity={}, error={}",
                    whatAction, whereEntityType, e.getMessage(), e);
        }
    }

    public void logCreate(AuditEntityType entityType, Long entityId, String entityName,
                          Object newState, String description, String module) {
        log(AuditAction.CREATE, entityType, entityId, entityName,
                null, newState, description, module, null);
    }

    public void logUpdate(AuditEntityType entityType, Long entityId, String entityName,
                          Object oldState, Object newState, String description, String module) {
        log(AuditAction.UPDATE, entityType, entityId, entityName,
                oldState, newState, description, module, null);
    }

    public void logDelete(AuditEntityType entityType, Long entityId, String entityName,
                          Object oldState, String description, String module) {
        log(AuditAction.DELETE, entityType, entityId, entityName,
                oldState, null, description, module, null);
    }

    public void logStatusChange(AuditEntityType entityType, Long entityId, String entityName,
                               Object oldState, Object newState, String description, String module) {
        log(AuditAction.STATUS_CHANGE, entityType, entityId, entityName,
                oldState, newState, description, module, null);
    }

    public void logCustom(AuditAction action, AuditEntityType entityType, Long entityId, String entityName,
                          Object beforeState, Object afterState, String description, String module,
                          Map<String, Object> metadata) {
        log(action, entityType, entityId, entityName,
                beforeState, afterState, description, module, metadata);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> searchAuditLogs(
            Long whoUserId,
            AuditAction whatAction,
            AuditEntityType whereEntityType,
            Long whereEntityId,
            Long contextTenantId,
            Instant startDate,
            Instant endDate,
            String module,
            Pageable pageable) {

        Page<AuditLog> logs = auditLogRepository.searchAuditLogs(
                whoUserId, whatAction, whereEntityType, whereEntityId,
                contextTenantId, startDate, endDate, module, pageable);

        return logs.map(adminMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLogsByUser(Long userId, Pageable pageable) {
        return auditLogRepository.findByWhoUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(adminMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLogsByEntity(
            AuditEntityType entityType, Long entityId, Pageable pageable) {
        return auditLogRepository
                .findByWhereEntityTypeAndWhereEntityIdOrderByCreatedAtDesc(entityType, entityId, pageable)
                .map(adminMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLogsByTenant(Long tenantId, Pageable pageable) {
        return auditLogRepository
                .findByContextTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(adminMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLogsByDateRange(
            Instant startDate, Instant endDate, Pageable pageable) {
        return auditLogRepository
                .findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate, pageable)
                .map(adminMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAuditStatistics(Instant since) {
        long totalCount = auditLogRepository.countLogsSince(since);
        List<Object[]> actionStats = auditLogRepository.getActionStatistics(since);

        Map<String, Long> actionCounts = actionStats.stream()
                .collect(Collectors.toMap(
                        row -> ((AuditAction) row[0]).name(),
                        row -> (Long) row[1]
                ));

        return Map.of(
                "totalCount", totalCount,
                "since", since.toString(),
                "actionCounts", actionCounts
        );
    }

    @Scheduled(cron = "${app.audit.cleanup-cron:0 0 2 * * ?}")
    @Transactional
    public void cleanupOldAuditLogs() {
        if (!auditEnabled) {
            return;
        }

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<AuditLog> allLogs = auditLogRepository.findAll();
        List<AuditLog> oldLogs = allLogs.stream()
                .filter(log -> log.getCreatedAt() != null && log.getCreatedAt().isBefore(cutoff))
                .collect(Collectors.toList());

        if (!oldLogs.isEmpty()) {
            auditLogRepository.deleteAll(oldLogs);
            log.info("Cleaned up {} audit logs older than {} days", oldLogs.size(), retentionDays);
        }
    }

    private Map<String, Object> toMap(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.convertValue(obj, Map.class);
        } catch (Exception e) {
            log.warn("Failed to convert object to map: {}", e.getMessage());
            return Map.of("value", obj.toString());
        }
    }

    private Map<String, Object> generateChangesSummary(Object before, Object after) {
        Map<String, Object> beforeMap = toMap(before);
        Map<String, Object> afterMap = toMap(after);

        if (beforeMap == null && afterMap == null) {
            return null;
        }

        if (beforeMap == null) {
            return Map.of("type", "CREATED", "fields", afterMap != null ? afterMap.keySet() : List.of());
        }

        if (afterMap == null) {
            return Map.of("type", "DELETED", "fields", beforeMap.keySet());
        }

        List<String> changedFields = beforeMap.entrySet().stream()
                .filter(e -> !java.util.Objects.equals(e.getValue(), afterMap.get(e.getKey())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return Map.of(
                "type", "UPDATED",
                "changedFields", changedFields,
                "changedCount", changedFields.size()
        );
    }

    private String determineSeverity(AuditAction action) {
        return switch (action) {
            case DELETE, ACCESS_REVOKED, PAYMENT_FAILED -> "WARN";
            case LOGIN, LOGOUT -> "INFO";
            default -> "INFO";
        };
    }
}
