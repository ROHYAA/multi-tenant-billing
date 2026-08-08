package com.mtbs.billing.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.billing.UsageMetric;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * @deprecated UsageSummary is deprecated. Use {@link UsageRecord} instead.
 * 
 * Migration notes:
 * - All usage data is now stored in UsageRecord
 * - UsageRecord.quantity = API_CALLS count
 * - UsageRecord.valueBytes = STORAGE_GB byte accumulation
 * - UsageRecord.isBilled = billing status (replaces UsageSummary.isBilled)
 * - ACTIVE_USERS is a live count from UserRepository, never stored
 * 
 * This entity will be removed in a future release.
 */
@Deprecated
@Entity
@Table(name = "usage_summaries")
@SQLDelete(sql = "UPDATE usage_summaries SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageSummary extends AuditableEntity {

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, length = 50)
    private UsageMetric metricType;

    @Column(name = "total_quantity", nullable = false)
    @Builder.Default
    private Long totalQuantity = 0L;

    @Column(name = "billing_period_start", nullable = false)
    private Instant billingPeriodStart;

    @Column(name = "billing_period_end", nullable = false)
    private Instant billingPeriodEnd;

    @Column(name = "is_billed", nullable = false)
    @Builder.Default
    private Boolean isBilled = false;
}
