package com.mtbs.tenant.service;

import com.mtbs.tenant.dto.plan.CreatePlanRequest;
import com.mtbs.tenant.dto.plan.PlanResponse;
import com.mtbs.tenant.dto.plan.UpdatePlanRequest;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.entity.PlanFeature;
import com.mtbs.tenant.entity.PlanLimit;
import com.mtbs.tenant.entity.PlanPricing;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.enums.billing.UsageMetric;
import com.mtbs.tenant.mapper.PlanMapper;
import com.mtbs.tenant.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanService {

    private final PlanRepository planRepository;
    private final PlanPricingRepository planPricingRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final PlanLimitRepository planLimitRepository;
    private final PlanMapper planMapper;

    /**
     * Gets all public plans that are active.
     * Used by unauthenticated users to view available plans.
     * Result is cached with planLimits cache (cleared on updates).
     */
    @Cacheable(
        value = "planLimits",
        key = "'all_public_plans'"
    )
    public List<PlanResponse> getAllPublicPlans() {
        List<Plan> plans = planRepository.findAllByIsActiveTrueAndIsPublicTrue();
        loadCollections(plans);
        return plans.stream()
                .map(planMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Gets all plans (including inactive). Admin-only.
     */
    public List<PlanResponse> getAllPlans() {
        List<Plan> plans = planRepository.findAll();
        loadCollections(plans);
        return plans.stream()
                .map(planMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Gets all active plans. Used internally and by services.
     */
    public List<PlanResponse> getAllActivePlans() {
        List<Plan> plans = planRepository.findByIsActiveTrueAndDeletedFalse();
        loadCollections(plans);
        return plans.stream()
                .map(planMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Gets a plan by ID as a response DTO.
     */
    public PlanResponse getPlanByIdAsResponse(Long id) {
        Plan plan = getPlanById(id);
        return planMapper.toResponse(plan);
    }

    /**
     * Gets a plan by ID as an entity (with collections loaded).
     * Throws ResourceException if not found.
     */
    public Plan getPlanById(Long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Plan", id));
        loadCollections(plan);
        return plan;
    }

    /**
     * Gets a plan by name (old method - kept for backward compatibility).
     */
    public Plan getPlanByName(String name) {
        Plan plan = planRepository.findByName(name)
                .orElseThrow(() -> ResourceException.notFound("Plan", name));
        loadCollections(plan);
        return plan;
    }

    /**
     * Gets a plan by code (case-insensitive).
     * NEW method for normalized structure.
     */
    public PlanResponse getPlanByCode(String code) {
        Plan plan = planRepository.findByCodeIgnoreCaseAndDeletedFalse(code)
                .orElseThrow(() -> ResourceException.notFound("Plan", code));
        loadCollections(plan);
        return planMapper.toResponse(plan);
    }

    /**
     * Gets a plan entity by code (case-insensitive).
     * Used by other services that need the Plan entity.
     */
    public Plan getPlanEntityByCode(String code) {
        return planRepository.findByCodeIgnoreCaseAndDeletedFalse(code)
                .orElseThrow(() -> ResourceException.notFound("Plan", code));
    }

    /**
     * Gets the next upgrade plan based on sort_order.
     * Uses database-driven logic instead of hardcoded strings.
     * @param currentPlanCode the current plan code (e.g., "FREE", "PRO")
     * @return Optional of the next plan, or empty if already at highest plan
     */
    public Optional<Plan> getNextPlan(String currentPlanCode) {
        Plan currentPlan = planRepository.findByCodeIgnoreCaseAndDeletedFalse(currentPlanCode)
                .orElse(null);
        
        if (currentPlan == null) {
            return Optional.empty();
        }
        
        return planRepository.findFirstBySortOrderGreaterThanAndIsActiveTrueAndDeletedFalseOrderBySortOrderAsc(
                currentPlan.getSortOrder());
    }

    /**
     * Gets a specific limit for a plan and metric.
     * NEW method used by PlanLimitService.
     */
    public Optional<PlanLimit> getPlanLimit(Long planId, UsageMetric metric) {
        return planLimitRepository.findByPlanIdAndMetricAndDeletedFalse(planId, metric);
    }

    /**
     * Checks if a feature is enabled for a plan.
     * NEW method used by feature gates.
     */
    public boolean isPlanFeatureEnabled(Long planId, String featureKey) {
        return planFeatureRepository.existsByPlanIdAndFeatureKeyAndEnabledTrueAndDeletedFalse(planId, featureKey);
    }

    /**
     * Creates a new plan with nested pricing, features, and limits.
     * @param request CreatePlanRequest with code, displayName, pricing, features, limits
     * @return created plan as PlanResponse
     * @throws ResourceException if plan code already exists
     */
    @Transactional
    @CacheEvict(value = "planLimits", allEntries = true)
    public PlanResponse createPlan(CreatePlanRequest request) {
        // Check code uniqueness
        if (planRepository.findByCodeAndDeletedFalse(request.getCode()).isPresent()) {
            throw ResourceException.alreadyExists("Plan", request.getCode());
        }

        // Create plan entity from scalar fields
        Plan plan = planMapper.toEntity(request);
        Plan savedPlan = planRepository.save(plan);
        log.info("Created plan: code={}, id={}", savedPlan.getCode(), savedPlan.getId());

        // Save pricing rows
        if (request.getPricing() != null && !request.getPricing().isEmpty()) {
            List<PlanPricing> pricingRows = request.getPricing().stream()
                    .map(pr -> PlanPricing.builder()
                            .plan(savedPlan)
                            .billingCycle(pr.getBillingCycle())
                            .price(pr.getPrice())
                            .currency(pr.getCurrency() != null ? 
                                    com.mtbs.shared.enums.billing.Currency.valueOf(pr.getCurrency()) :
                                    com.mtbs.shared.enums.billing.Currency.INR)
                            .trialDays(pr.getTrialDays() != null ? pr.getTrialDays() : 0)
                            .isDefault(pr.getIsDefault() != null ? pr.getIsDefault() : false)
                            .build())
                    .collect(Collectors.toList());
            planPricingRepository.saveAll(pricingRows);
        }

        // Save feature rows
        if (request.getFeatures() != null && !request.getFeatures().isEmpty()) {
            List<PlanFeature> featureRows = request.getFeatures().stream()
                    .map(fr -> PlanFeature.builder()
                            .plan(savedPlan)
                            .featureKey(fr.getFeatureKey())
                            .enabled(fr.getEnabled() != null ? fr.getEnabled() : true)
                            .build())
                    .collect(Collectors.toList());
            planFeatureRepository.saveAll(featureRows);
        }

        // Save limit rows
        if (request.getLimits() != null && !request.getLimits().isEmpty()) {
            List<PlanLimit> limitRows = request.getLimits().stream()
                    .map(lr -> PlanLimit.builder()
                            .plan(savedPlan)
                            .metric(lr.getMetric())
                            .value(lr.getValue())
                            .unlimited(lr.getUnlimited() != null ? lr.getUnlimited() : false)
                            .build())
                    .collect(Collectors.toList());
            planLimitRepository.saveAll(limitRows);
        }

        return getPlanByIdAsResponse(savedPlan.getId());
    }

    /**
     * Updates an existing plan.
     * Scalar fields are updated if not null.
     * Collections (pricing, features, limits) are replaced if provided.
     * @param id plan ID
     * @param request UpdatePlanRequest with optional fields
     * @return updated plan as PlanResponse
     * @throws ResourceException if plan not found
     */
    @Transactional
    @CacheEvict(value = "planLimits", allEntries = true)
    public PlanResponse updatePlan(Long id, UpdatePlanRequest request) {

        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Plan", id));

        // =============================
        // 1. Update scalar fields
        // =============================
        if (request.getCode() != null) {
            plan.setCode(request.getCode());
        }

        if (request.getDisplayName() != null) {
            plan.setDisplayName(request.getDisplayName());
        }

        if (request.getDescription() != null) {
            plan.setDescription(request.getDescription());
        }

        if (request.getIsActive() != null) {
            plan.setIsActive(request.getIsActive());
        }

        if (request.getIsPublic() != null) {
            plan.setIsPublic(request.getIsPublic());
        }

        if (request.getSortOrder() != null) {
            plan.setSortOrder(request.getSortOrder());
        }

        if (request.getBadge() != null) {
            plan.setBadge(request.getBadge());
        }

        // Save parent first
        Plan savedPlan = planRepository.save(plan);

        log.info("Updated plan basic details: id={}, code={}",
                savedPlan.getId(),
                savedPlan.getCode());


        // =============================
        // 2. Replace Pricing (HARD DELETE + FLUSH + INSERT)
        // =============================
        if (request.getPricing() != null) {

            planPricingRepository.deleteByPlanId(savedPlan.getId());
            planPricingRepository.flush();

            if (!request.getPricing().isEmpty()) {
                List<PlanPricing> pricingList = request.getPricing()
                        .stream()
                        .map(pr -> PlanPricing.builder()
                                .plan(savedPlan)
                                .billingCycle(pr.getBillingCycle())
                                .price(pr.getPrice())
                                .currency(
                                        pr.getCurrency() != null
                                                ? com.mtbs.shared.enums.billing.Currency.valueOf(pr.getCurrency())
                                                : com.mtbs.shared.enums.billing.Currency.INR
                                )
                                .trialDays(
                                        pr.getTrialDays() != null
                                                ? pr.getTrialDays()
                                                : 0
                                )
                                .isDefault(
                                        pr.getIsDefault() != null
                                                ? pr.getIsDefault()
                                                : false
                                )
                                .build())
                        .toList();

                planPricingRepository.saveAll(pricingList);
            }
        }


        // =============================
        // 3. Replace Features (HARD DELETE + FLUSH + INSERT)
        // =============================
        if (request.getFeatures() != null) {

            planFeatureRepository.deleteByPlanId(savedPlan.getId());
            planFeatureRepository.flush();

            if (!request.getFeatures().isEmpty()) {
                List<PlanFeature> featureList = request.getFeatures()
                        .stream()
                        .map(fr -> PlanFeature.builder()
                                .plan(savedPlan)
                                .featureKey(fr.getFeatureKey())
                                .enabled(
                                        fr.getEnabled() != null
                                                ? fr.getEnabled()
                                                : true
                                )
                                .build())
                        .toList();

                planFeatureRepository.saveAll(featureList);
            }
        }


        // =============================
        // 4. Replace Limits (HARD DELETE + FLUSH + INSERT)
        // =============================
        if (request.getLimits() != null) {

            planLimitRepository.deleteByPlanId(savedPlan.getId());
            planLimitRepository.flush();

            if (!request.getLimits().isEmpty()) {
                List<PlanLimit> limitList = request.getLimits()
                        .stream()
                        .map(lr -> PlanLimit.builder()
                                .plan(savedPlan)
                                .metric(lr.getMetric())
                                .value(lr.getValue())
                                .unlimited(
                                        lr.getUnlimited() != null
                                                ? lr.getUnlimited()
                                                : false
                                )
                                .build())
                        .toList();

                planLimitRepository.saveAll(limitList);
            }
        }

        log.info("Plan fully updated successfully: id={}", savedPlan.getId());

        return getPlanByIdAsResponse(savedPlan.getId());
    }

    /**
     * Deactivates a plan (soft-delete).
     */
    @Transactional
    @CacheEvict(value = "planLimits", allEntries = true)
    public void deactivatePlan(Long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Plan", id));
        plan.setIsActive(false);
        plan.setDeleted(true);
        plan.setDeletedAt(Instant.now());
        planRepository.save(plan);
        log.info("Deactivated plan: id={}, code={}", plan.getId(), plan.getCode());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public Helper Methods for Backward Compatibility (Replaces removed Plan fields)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Gets the price for a specific billing cycle.
     * @param planId the plan ID
     * @param cycle the billing cycle (MONTHLY or ANNUAL)
     * @return the price as BigDecimal, or BigDecimal.ZERO if not found
     */
    public java.math.BigDecimal getPriceForCycle(Long planId, com.mtbs.shared.enums.billing.BillingCycle cycle) {
        return planPricingRepository
                .findByPlanIdAndBillingCycleAndDeletedFalse(planId, cycle)
                .map(PlanPricing::getPrice)
                .orElse(java.math.BigDecimal.ZERO);
    }

    /**
     * Gets the monthly price for a plan (convenience method).
     * @param planId the plan ID
     * @return the monthly price as BigDecimal, or BigDecimal.ZERO if not found
     */
    public java.math.BigDecimal getPriceMonthly(Long planId) {
        return getPriceForCycle(planId, com.mtbs.shared.enums.billing.BillingCycle.MONTHLY);
    }

    /**
     * Gets the annual price for a plan (convenience method).
     * @param planId the plan ID
     * @return the annual price as BigDecimal, or BigDecimal.ZERO if not found
     */
    public java.math.BigDecimal getPriceAnnual(Long planId) {
        return getPriceForCycle(planId, com.mtbs.shared.enums.billing.BillingCycle.ANNUAL);
    }

    /**
     * Gets the currency for a plan (from default pricing or fallback).
     * @param planId the plan ID
     * @return the currency code as String (e.g., "INR", "USD"), or "INR" as fallback
     */
    public String getCurrencyForPlan(Long planId) {
        return planPricingRepository
                .findByPlanIdAndIsDefaultTrueAndDeletedFalse(planId)
                .map(p -> p.getCurrency() != null ? p.getCurrency().name() : "INR")
                .orElse("INR");
    }

    /**
     * Gets the trial days for a specific billing cycle.
     * @param planId the plan ID
     * @param cycle the billing cycle (MONTHLY or ANNUAL)
     * @return the trial days as Integer, or 0 if not found
     */
    public Integer getTrialDaysForPlan(Long planId, com.mtbs.shared.enums.billing.BillingCycle cycle) {
        return planPricingRepository
                .findByPlanIdAndBillingCycleAndDeletedFalse(planId, cycle)
                .map(PlanPricing::getTrialDays)
                .orElse(0);
    }

    /**
     * Gets the limit value for a specific metric.
     * @param planId the plan ID
     * @param metric the usage metric (ACTIVE_USERS, API_CALLS, STORAGE_GB)
     * @return the limit value as Long, or null if unlimited or not found
     */
    public Long getLimitValue(Long planId, UsageMetric metric) {
        return planLimitRepository
                .findByPlanIdAndMetricAndDeletedFalse(planId, metric)
                .filter(l -> !Boolean.TRUE.equals(l.getUnlimited()))
                .map(PlanLimit::getValue)
                .orElse(null);
    }

    /**
     * Checks if a limit is unlimited.
     * @param planId the plan ID
     * @param metric the usage metric (ACTIVE_USERS, API_CALLS, STORAGE_GB)
     * @return true if unlimited, false if capped or not found
     */
    public boolean isLimitUnlimited(Long planId, UsageMetric metric) {
        return planLimitRepository
                .findByPlanIdAndMetricAndDeletedFalse(planId, metric)
                .map(PlanLimit::getUnlimited)
                .orElse(false);
    }

    /**
     * Gets the max users limit (convenience method for ACTIVE_USERS metric).
     * @param planId the plan ID
     * @return the max users as Long, or null if unlimited or not found
     */
    public Long getMaxUsers(Long planId) {
        return getLimitValue(planId, UsageMetric.ACTIVE_USERS);
    }

    /**
     * Gets the max API calls per month limit (convenience method for API_CALLS metric).
     * @param planId the plan ID
     * @return the max API calls as Long, or null if unlimited or not found
     */
    public Long getMaxApiCallsPerMonth(Long planId) {
        return getLimitValue(planId, UsageMetric.API_CALLS);
    }

    /**
     * Gets the max storage in GB limit (convenience method for STORAGE_GB metric).
     * @param planId the plan ID
     * @return the max storage GB as Long, or null if unlimited or not found
     */
    public Long getMaxStorageGb(Long planId) {
        return getLimitValue(planId, UsageMetric.STORAGE_GB);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private Helper Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads collections (pricing, features, limits) for a single plan.
     * This is needed because @OneToMany is LAZY.
     */
    private void loadCollections(Plan plan) {
        if (plan != null) {
            plan.setPricing(planPricingRepository.findByPlanIdAndDeletedFalse(plan.getId()));
            plan.setFeatures(planFeatureRepository.findByPlanIdAndDeletedFalse(plan.getId()));
            plan.setLimits(planLimitRepository.findByPlanIdAndDeletedFalse(plan.getId()));
        }
    }

    /**
     * Loads collections for multiple plans.
     */
    private void loadCollections(List<Plan> plans) {
        if (plans != null) {
            plans.forEach(this::loadCollections);
        }
    }
}
