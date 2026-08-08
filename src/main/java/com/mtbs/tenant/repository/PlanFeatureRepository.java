package com.mtbs.tenant.repository;

import com.mtbs.tenant.entity.PlanFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanFeatureRepository extends JpaRepository<PlanFeature, Long> {

    /**
     * Finds all features for a specific plan (excluding soft-deleted).
     * @param planId the plan ID
     * @return list of feature rows for this plan
     */
    List<PlanFeature> findByPlanIdAndDeletedFalse(Long planId);

    /**
     * Finds a specific feature for a plan by feature key.
     * @param planId the plan ID
     * @param featureKey the feature key (e.g., API_ACCESS, PDF_EXPORT)
     * @return Optional containing the feature if found
     */
    Optional<PlanFeature> findByPlanIdAndFeatureKeyAndDeletedFalse(Long planId, String featureKey);

    /**
     * Checks if a specific feature is enabled for a plan.
     * Useful for quick feature gate checks without loading the full entity.
     * @param planId the plan ID
     * @param featureKey the feature key
     * @return true if the feature exists and is enabled, false otherwise
     */
    boolean existsByPlanIdAndFeatureKeyAndEnabledTrueAndDeletedFalse(Long planId, String featureKey);

    @Modifying
    @Query("DELETE FROM PlanFeature f WHERE f.plan.id = :planId")
    void deleteByPlanId(@Param("planId") Long planId);
}
