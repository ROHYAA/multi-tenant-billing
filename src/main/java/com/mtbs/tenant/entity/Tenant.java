package com.mtbs.tenant.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.auth.Status;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "tenants", schema = "public")
@SQLDelete(sql = "UPDATE public.tenants SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant extends AuditableEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "schema_name", nullable = false, unique = true)
    private String schemaName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "owner_email", length = 255)
    private String ownerEmail;

    @Column(name = "slug", length = 63, unique = true)
    private String slug;

    @Column(name = "onboarding_step", nullable = false)
    @Builder.Default
    private Integer onboardingStep = 0;

}
