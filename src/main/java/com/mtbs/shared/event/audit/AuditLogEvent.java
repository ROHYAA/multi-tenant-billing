package com.mtbs.shared.event.audit;

import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.event.DomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEvent implements DomainEvent {

    private AuditAction action;
    private AuditEntityType entityType;
    private Long entityId;
    private String entityName;

    private Long whoUserId;
    private String whoUserEmail;
    private String whoUserName;
    private String whoRole;

    private Long contextTenantId;
    private String contextTenantName;
    private String contextIpAddress;
    private String contextUserAgent;

    private Map<String, Object> changesBefore;
    private Map<String, Object> changesAfter;
    private Map<String, Object> changesSummary;

    private String description;
    private String module;
    private String severity;

    private Map<String, Object> metadata;

    @Override
    public String getEventType() {
        return action != null ? "AUDIT_" + action.name() : null;
    }
}
