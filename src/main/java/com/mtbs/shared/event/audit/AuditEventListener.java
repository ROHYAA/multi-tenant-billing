package com.mtbs.shared.event.audit;

import com.mtbs.admin.repository.AuditLogRepository;
import com.mtbs.admin.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventListener {

    private final AuditLogRepository auditLogRepository;

    @Async("auditEventExecutor")
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAuditEvent(AuditLogEvent event) {
        log.debug("AUDIT_EVENT_RECEIVED action={} entityType={} entityId={}",
                event.getAction(), event.getEntityType(), event.getEntityId());

        try {
            AuditLog auditLog = AuditLog.builder()
                    .whoUserId(event.getWhoUserId())
                    .whoUserEmail(event.getWhoUserEmail())
                    .whoUserName(event.getWhoUserName())
                    .whoRole(event.getWhoRole())
                    .whatAction(event.getAction())
                    .whereEntityType(event.getEntityType())
                    .whereEntityId(event.getEntityId())
                    .whereEntityName(event.getEntityName())
                    .changesBefore(event.getChangesBefore())
                    .changesAfter(event.getChangesAfter())
                    .changesSummary(event.getChangesSummary())
                    .contextTenantId(event.getContextTenantId())
                    .contextTenantName(event.getContextTenantName())
                    .contextIpAddress(event.getContextIpAddress())
                    .contextUserAgent(event.getContextUserAgent())
                    .contextMetadata(event.getMetadata())
                    .description(event.getDescription())
                    .module(event.getModule())
                    .severity(event.getSeverity() != null ? event.getSeverity() : "INFO")
                    .build();

            auditLogRepository.save(auditLog);

            log.info("AUDIT_EVENT_SAVED action={} entityType={} entityId={} who={}",
                    event.getAction(), event.getEntityType(), event.getEntityId(), event.getWhoUserEmail());

        } catch (Exception e) {
            log.error("AUDIT_EVENT_FAILED action={} entityType={} error={}",
                    event.getAction(), event.getEntityType(), e.getMessage(), e);
        }
    }
}
