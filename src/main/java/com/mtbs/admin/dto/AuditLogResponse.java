package com.mtbs.admin.dto;

import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import lombok.*;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private Long id;

    private Long whoUserId;
    private String whoUserEmail;
    private String whoUserName;
    private String whoRole;

    private AuditAction whatAction;
    private String whatDescription;

    private AuditEntityType whereEntityType;
    private Long whereEntityId;
    private String whereEntityName;

    private Map<String, Object> changesBefore;
    private Map<String, Object> changesAfter;
    private Map<String, Object> changesSummary;

    private Long contextTenantId;
    private String contextTenantName;
    private String contextIpAddress;
    private String contextUserAgent;
    private Map<String, Object> contextMetadata;

    private String description;
    private String module;
    private String severity;

    private Instant createdAt;
}
