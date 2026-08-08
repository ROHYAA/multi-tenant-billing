package com.mtbs.admin.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "audit_logs", schema = "public")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog extends AuditableEntity {

    @Column(name = "who_user_id")
    private Long whoUserId;

    @Column(name = "who_user_email", length = 255)
    private String whoUserEmail;

    @Column(name = "who_user_name", length = 255)
    private String whoUserName;

    @Column(name = "who_role", length = 100)
    private String whoRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "what_action", nullable = false, length = 50)
    private AuditAction whatAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "where_entity_type", nullable = false, length = 50)
    private AuditEntityType whereEntityType;

    @Column(name = "where_entity_id")
    private Long whereEntityId;

    @Column(name = "where_entity_name", length = 500)
    private String whereEntityName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes_before", columnDefinition = "jsonb")
    private Map<String, Object> changesBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes_after", columnDefinition = "jsonb")
    private Map<String, Object> changesAfter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes_summary", columnDefinition = "jsonb")
    private Map<String, Object> changesSummary;

    @Column(name = "context_tenant_id")
    private Long contextTenantId;

    @Column(name = "context_tenant_name", length = 255)
    private String contextTenantName;

    @Column(name = "context_ip_address", length = 45)
    private String contextIpAddress;

    @Column(name = "context_user_agent", length = 500)
    private String contextUserAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_metadata", columnDefinition = "jsonb")
    private Map<String, Object> contextMetadata;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "module", length = 100)
    private String module;

    @Column(name = "severity", length = 20)
    @Builder.Default
    private String severity = "INFO";
}
