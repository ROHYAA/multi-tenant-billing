package com.mtbs.tenant.repository;

import com.mtbs.tenant.entity.PlanPricing;
import com.mtbs.shared.enums.billing.BillingCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanPricingRepository extends JpaRepository<PlanPricing, Long> {

    /**
     * Finds all pricing rows for a specific plan (excluding soft-deleted).
     * @param planId the plan ID
     * @return list of pricing rows for this plan
     */
    List<PlanPricing> findByPlanIdAndDeletedFalse(Long planId);

    /**
     * Finds pricing for a specific plan and billing cycle.
     * @param planId the plan ID
     * @param billingCycle the billing cycle (MONTHLY or ANNUAL)
     * @return Optional containing the pricing if found
     */
    Optional<PlanPricing> findByPlanIdAndBillingCycleAndDeletedFalse(Long planId, BillingCycle billingCycle);

    /**
     * Finds the default pricing row for a plan (the one shown by default on UI).
     * @param planId the plan ID
     * @return Optional containing the default pricing if found
     */
    Optional<PlanPricing> findByPlanIdAndIsDefaultTrueAndDeletedFalse(Long planId);

    @Modifying
    @Query("DELETE FROM PlanPricing p WHERE p.plan.id = :planId")
    void deleteByPlanId(@Param("planId") Long planId);
}
