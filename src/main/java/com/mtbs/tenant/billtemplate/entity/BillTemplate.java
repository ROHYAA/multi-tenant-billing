package com.mtbs.tenant.billtemplate.entity;

import com.mtbs.shared.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Platform-wide catalog of bill layouts every shop can pick from — lives in
 * public schema, same tier as Permission (one list, all tenant schemas
 * reference it by ID without an enforced cross-schema FK, matching the
 * existing role_permissions -> public.permissions precedent).
 *
 * `code` bridges a catalog row to the Java BillTemplateRenderer that
 * actually draws the PDF for it (business.invoice.template package).
 * Catalog rows are migration-seeded only for V1 — no write endpoint yet.
 */
@Entity
@Table(name = "bill_templates", schema = "public")
@SQLDelete(sql = "UPDATE public.bill_templates SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillTemplate extends AuditableEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
