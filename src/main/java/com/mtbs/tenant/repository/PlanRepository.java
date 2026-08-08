package com.mtbs.tenant.repository;

import com.mtbs.tenant.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    // ── Existing methods (maintained for backward compatibility) ──────────────

    Optional<Plan> findByName(String name);

    List<Plan> findAllByIsActiveTrue();

    List<Plan> findAllByIsActiveTrueAndIsPublicTrue();

    boolean existsByName(String name);

    // ── New methods (for normalized structure) ────────────────────────────────

    /**
     * Finds a plan by its code (case-sensitive). Used internally for code-based lookups.
     * @param code the plan code (e.g., FREE, PRO, ENTERPRISE)
     * @return Optional containing the plan if found and not deleted
     */
    Optional<Plan> findByCodeAndDeletedFalse(String code);

    /**
     * Finds a plan by its code (case-insensitive). Used for API requests.
     * @param code the plan code (case-insensitive)
     * @return Optional containing the plan if found and not deleted
     */
    Optional<Plan> findByCodeIgnoreCaseAndDeletedFalse(String code);

    /**
     * Finds all active non-deleted plans. Ordered by sort_order.
     * @return list of active plans
     */
    List<Plan> findByIsActiveTrueAndDeletedFalse();

    /**
     * Finds the next higher plan based on sort_order.
     * @param currentSortOrder the current plan's sort order
     * @return Optional of the next plan with higher sort order
     */
    Optional<Plan> findFirstBySortOrderGreaterThanAndIsActiveTrueAndDeletedFalseOrderBySortOrderAsc(Integer currentSortOrder);
}

