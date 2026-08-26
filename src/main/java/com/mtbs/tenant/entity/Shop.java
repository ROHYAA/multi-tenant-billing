package com.mtbs.tenant.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.auth.Status;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

@Entity
@Table(name = "shops", schema = "public")
@SQLDelete(sql = "UPDATE public.shops SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shop extends AuditableEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "schema_name", nullable = false, unique = true)
    private String schemaName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "owner_email", length = 255)
    private String ownerEmail;

    @Column(name = "slug", length = 63, unique = true)
    private String slug;

    /** Offline-payment tracking — free-text plan label + expiry, set by admin on approve/reactivate. */
    @Column(name = "plan_name", length = 100)
    private String planName;

    @Column(name = "subscription_expires_at")
    private Instant subscriptionExpiresAt;

    /** Set once the 5-day pre-expiry alert fires; reset to null on renewal so it can fire again next cycle. */
    @Column(name = "expiry_alert_sent_at")
    private Instant expiryAlertSentAt;

}
