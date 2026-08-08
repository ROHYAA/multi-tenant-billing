package com.mtbs.billing.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.billing.UsageMetric;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

@Entity
@Table(name = "usage_records")
@SQLDelete(sql = "UPDATE usage_records SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageRecord extends AuditableEntity {

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, length = 50)
    private UsageMetric metricType;

    @Column(nullable = false)
    @Builder.Default
    private Long quantity = 0L;

    @Builder.Default
    @Column(name = "value_bytes", nullable = false)
    private Long valueBytes = 0L;

    @Column(name = "is_billed", nullable = false)
    @Builder.Default
    private Boolean isBilled = false;

    @Column(name = "recorded_at", nullable = false)
    @Builder.Default
    private Instant recordedAt = Instant.now();

    @Column(name = "billing_period_start", nullable = false)
    private Instant billingPeriodStart;

    @Column(name = "billing_period_end", nullable = false)
    private Instant billingPeriodEnd;
}
