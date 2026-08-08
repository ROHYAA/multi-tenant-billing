package com.mtbs.tenant.service;

import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.repository.UsageRecordRepository;
import com.mtbs.billing.service.SubscriptionService;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.enums.billing.UsageMetric;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.dto.plan.PlanLimits;
import com.mtbs.tenant.dto.plan.UsageLimitsResponse;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.entity.PlanLimit;
import com.mtbs.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for enforcing plan limits and providing limit information.
 * 
 * Limits can be checked in two modes:
 * 1. ActiveUser limits: counted from the users table (live count)
 * 2. MetricUsage limits: counted from usage_records table (within billing period)
 * 
 * Limit structure (new normalized):
 * - Each limit is a row in plan_limits
 * - metric = ACTIVE_USERS | API_CALLS | STORAGE_GB
 * - unlimited = true means no cap
 * - value = the cap (NULL when unlimited)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanLimitService {

    private final SubscriptionService subscriptionService;
    private final PlanService planService;
    private final UserRepository userRepository;
    private final UsageRecordRepository usageRecordRepository;

    /**
     * Gets current limits for the tenant.
     * Uses plan_limits table instead of flat Plan fields.
     * Result is cached by tenant ID.
     */
    @Cacheable(
        value = "planLimits",
        key = "T(com.mtbs.shared.multitenancy.TenantContext).getTenantId() + ':limits'"
    )
    public PlanLimits getCurrentLimits() {
        Long tenantId = TenantContext.getTenantId();
        
        Optional<Subscription> subOpt = subscriptionService.findFirstSubscriptionByStatuses(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING));

        // If no subscription, return limited FREE plan limits
        if (subOpt.isEmpty()) {
            log.warn("No active subscription for tenant: {}, falling back to FREE plan limits", tenantId);
            Plan freePlan = planService.getPlanByName("FREE");
            return buildPlanLimitsFromPlan(freePlan);
        }

        Subscription subscription = subOpt.get();
        Plan plan = planService.getPlanById(subscription.getPlanId());
        return buildPlanLimitsFromPlan(plan);
    }

    /**
     * Checks if a tenant can perform an action with a specific metric.
     * Returns true if within limit, false if exceeded.
     * Throws ResourceException if limit is exceeded (used by interceptor).
     * 
     * @param metric the usage metric (ACTIVE_USERS, API_CALLS, STORAGE_GB)
     * @return true if within limit
     * @throws ResourceException if limit exceeded
     */
    public boolean checkLimit(UsageMetric metric) {
        Optional<Subscription> subOpt = subscriptionService.findFirstSubscriptionByStatuses(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING));

        if (subOpt.isEmpty()) {
            log.warn("No active subscription for tenant");
            return true; // No limit if no subscription
        }

        Subscription subscription = subOpt.get();
        Long planId = subscription.getPlanId();

        // Get the limit for this metric
        Optional<PlanLimit> limitOpt = planService.getPlanLimit(planId, metric);
        if (limitOpt.isEmpty()) {
            return true; // No limit row = unlimited
        }

        PlanLimit limit = limitOpt.get();
        if (Boolean.TRUE.equals(limit.getUnlimited())) {
            return true; // Explicitly unlimited
        }

        // Compute current usage
        long currentUsage = computeCurrentUsage(subscription, metric);

        // Check if within limit
        boolean withinLimit = limit.isWithinLimit(currentUsage);
        if (!withinLimit) {
            long limitValue = limit.getValue() != null ? limit.getValue() : 0;
            throw ResourceException.planLimitExceeded(metric.name(), limitValue, currentUsage);
        }

        return true;
    }

    /**
     * Checks API call limit specifically.
     * Used by request interceptor to enforce API call caps.
     */
    public void checkApiCallLimit(long currentApiCalls) {
        Optional<Subscription> subOpt = subscriptionService.findFirstSubscriptionByStatuses(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING));

        if (subOpt.isEmpty()) {
            return; // No subscription = no limit
        }

        Subscription subscription = subOpt.get();
        Optional<PlanLimit> limitOpt = planService.getPlanLimit(subscription.getPlanId(), UsageMetric.API_CALLS);

        if (limitOpt.isEmpty() || Boolean.TRUE.equals(limitOpt.get().getUnlimited())) {
            return; // Unlimited
        }

        PlanLimit limit = limitOpt.get();
        if (!limit.isWithinLimit(currentApiCalls)) {
            long limitValue = limit.getValue() != null ? limit.getValue() : 0;
            throw ResourceException.planLimitExceeded("API_CALLS", limitValue, currentApiCalls);
        }
    }

    /**
     * Enforces limit for a specific metric (used by aspects/interceptors).
     * Throws exception if limit is exceeded.
     */
    public void enforceLimitForMetric(UsageMetric metric) {
        // API_CALLS is handled separately by checkApiCallLimit (dynamic)
        if (metric == UsageMetric.API_CALLS) {
            return;
        }

        Optional<Subscription> subOpt = subscriptionService.findFirstSubscriptionByStatuses(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING));

        if (subOpt.isEmpty()) {
            throw ResourceException.planLimitExceeded(metric.name(), 0, 0);
        }

        Subscription subscription = subOpt.get();
        Optional<PlanLimit> limitOpt = planService.getPlanLimit(subscription.getPlanId(), metric);

        if (limitOpt.isEmpty() || Boolean.TRUE.equals(limitOpt.get().getUnlimited())) {
            return; // Unlimited
        }

        PlanLimit limit = limitOpt.get();
        long currentUsage = computeCurrentUsage(subscription, metric);

        if (!limit.isWithinLimit(currentUsage)) {
            long limitValue = limit.getValue() != null ? limit.getValue() : 0;
            throw ResourceException.planLimitExceeded(metric.name(), limitValue, currentUsage);
        }
    }

    /**
     * Enforces user limit specifically.
     * Used by user creation/management to prevent exceeding plan limits.
     */
    public void enforceUserLimit() {
        PlanLimits limits = getCurrentLimits();
        
        // Check if there's an ACTIVE_USERS limit
        long currentUsers = userRepository.countActiveUsers();
        
        // If no limit (unlimited=true), allow
        if (limits.isUnlimited(UsageMetric.ACTIVE_USERS)) {
            return;
        }

        // Get the actual limit value
        long maxUsers = 3; // Default conservative value if not found
        for (var limit : limits.getLimits()) {
            if (limit.getMetric() == UsageMetric.ACTIVE_USERS && !limit.getUnlimited()) {
                maxUsers = limit.getValue();
                break;
            }
        }

        if (currentUsers >= maxUsers) {
            throw ResourceException.planLimitExceeded("ACTIVE_USERS", maxUsers, currentUsers);
        }
    }

    /**
     * Gets comprehensive usage limits with current usage.
     * Used by dashboard/admin panels to show usage progress.
     */
    public UsageLimitsResponse getLimits() {
        Optional<Subscription> subOpt = subscriptionService.findFirstSubscriptionByStatuses(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING));

        if (subOpt.isEmpty()) {
            return UsageLimitsResponse.builder().build();
        }

        Subscription subscription = subOpt.get();
        Plan plan = planService.getPlanById(subscription.getPlanId());

        List<UsageLimitsResponse.UsageMetricLimit> metricLimits = new ArrayList<>();

        for (var planLimit : plan.getLimits()) {
            long currentUsage = computeCurrentUsage(subscription, planLimit.getMetric());
            long limitValue = planLimit.getValue() != null ? planLimit.getValue() : 0;
            boolean unlimited = Boolean.TRUE.equals(planLimit.getUnlimited());

            String displayLimit = unlimited ? "Unlimited" : String.valueOf(limitValue);
            double usagePercent = unlimited ? 0.0 : 
                (limitValue > 0 ? (currentUsage * 100.0) / limitValue : 0.0);

            metricLimits.add(UsageLimitsResponse.UsageMetricLimit.builder()
                    .metric(planLimit.getMetric())
                    .limitValue(unlimited ? null : limitValue)
                    .unlimited(unlimited)
                    .currentUsage(currentUsage)
                    .displayLimit(displayLimit)
                    .usagePercent(usagePercent)
                    .build());
        }

        return UsageLimitsResponse.builder()
                .planId(plan.getId())
                .planCode(plan.getCode())
                .metrics(metricLimits)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private Helper Methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds a PlanLimits DTO from a Plan entity (for backward compatibility).
     * Extracts limit information from plan_limits rows.
     */
    private PlanLimits buildPlanLimitsFromPlan(Plan plan) {
        // For backward compatibility, check if any limit is marked as unlimited
        boolean anyUnlimited = plan.getLimits().stream()
                .anyMatch(l -> Boolean.TRUE.equals(l.getUnlimited()));

        return PlanLimits.builder()
                .planId(plan.getId())
                .planCode(plan.getCode())
                .build();
    }

    /**
     * Computes the current usage for a metric within a subscription period.
     * Different logic per metric:
     * - ACTIVE_USERS: live count from users table
     * - API_CALLS: sum from usage_records during billing period
     * - STORAGE_GB: sum from usage_records during billing period
     */
    private long computeCurrentUsage(Subscription subscription, UsageMetric metric) {
        return switch (metric) {
            case ACTIVE_USERS -> userRepository.countActiveUsers();
            case API_CALLS -> usageRecordRepository
                    .sumQuantityByMetricTypeAndBillingPeriodStartAndBillingPeriodEnd(
                            metric, subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd());
            case STORAGE_GB -> usageRecordRepository
                    .sumQuantityByMetricTypeAndBillingPeriodStartAndBillingPeriodEnd(
                            metric, subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd());
        };
    }
}
