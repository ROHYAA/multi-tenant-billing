package com.mtbs.tenant.repository;

import com.mtbs.tenant.entity.PlanLimit;
import com.mtbs.shared.enums.billing.UsageMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanLimitRepository extends JpaRepository<PlanLimit, Long> {

    /**
     * Finds all limits for a specific plan (excluding soft-deleted).
     * @param planId the plan ID
     * @return list of limit rows for this plan
     */
    List<PlanLimit> findByPlanIdAndDeletedFalse(Long planId);

    /**
     * Finds a specific limit for a plan and metric.
     * @param planId the plan ID
     * @param metric the usage metric (ACTIVE_USERS, API_CALLS, STORAGE_GB)
     * @return Optional containing the limit if found
     */
    Optional<PlanLimit> findByPlanIdAndMetricAndDeletedFalse(Long planId, UsageMetric metric);

    @Modifying
    @Query("DELETE FROM PlanLimit l WHERE l.plan.id = :planId")
    void deleteByPlanId(@Param("planId") Long planId);
}
