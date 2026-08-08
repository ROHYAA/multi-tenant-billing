package com.mtbs.tenant.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.billing.UsageMetric;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "plan_limits", schema = "public")
@SQLDelete(sql = "UPDATE public.plan_limits SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanLimit extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UsageMetric metric;

    @Column(nullable = true)
    private Long value;

    @Column(nullable = false)
    @Builder.Default
    private Boolean unlimited = false;

    /**
     * Checks if the given usage is within this limit.
     * If unlimited=true or value is null, always returns true.
     * Otherwise, returns true if currentUsage < value.
     *
     * @param currentUsage the current usage count
     * @return true if usage is within limit, false if exceeded
     */
    public boolean isWithinLimit(long currentUsage) {
        if (Boolean.TRUE.equals(unlimited) || value == null) {
            return true;
        }
        return currentUsage < value;
    }

    /**
     * Returns the effective limit value.
     * If unlimited=true or value is null, returns Long.MAX_VALUE.
     * Otherwise, returns the configured value.
     *
     * @return the effective limit (Long.MAX_VALUE if unlimited, otherwise value)
     */
    public long effectiveLimit() {
        return (Boolean.TRUE.equals(unlimited) || value == null)
                ? Long.MAX_VALUE
                : value;
    }
}
