package com.mtbs.tenant.entity;

import com.mtbs.shared.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "plans", schema = "public")
@SQLDelete(sql = "UPDATE public.plans SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"pricing", "features", "limits"})
public class Plan extends AuditableEntity {

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(length = 50)
    private String badge;

    // ──────────────────────────────────────────────────────────────────────────
    // Collections
    // ──────────────────────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "plan", fetch = FetchType.LAZY)
    @Builder.Default
    private List<PlanPricing> pricing = new ArrayList<>();

    @OneToMany(mappedBy = "plan", fetch = FetchType.LAZY)
    @Builder.Default
    private List<PlanFeature> features = new ArrayList<>();

    @OneToMany(mappedBy = "plan", fetch = FetchType.LAZY)
    @Builder.Default
    private List<PlanLimit> limits = new ArrayList<>();
}