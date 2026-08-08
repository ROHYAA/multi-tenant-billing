package com.mtbs.billing.mapper;

import com.mtbs.billing.dto.subscription.SubscriptionResponse;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.tenant.entity.Plan;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface SubscriptionMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "planId", source = "planId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "billingCycle", source = "billingCycle")
    @Mapping(target = "trialStart", source = "trialStart")
    @Mapping(target = "trialEnd", source = "trialEnd")
    @Mapping(target = "trialDaysRemaining", ignore = true)
    @Mapping(target = "currentPeriodStart", source = "currentPeriodStart")
    @Mapping(target = "currentPeriodEnd", source = "currentPeriodEnd")
    @Mapping(target = "priceMonthly", ignore = true)
    @Mapping(target = "priceAnnual", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "cancelledAt", source = "cancelledAt")
    @Mapping(target = "cancelAtPeriodEnd", source = "cancelAtPeriodEnd")
    @Mapping(target = "upgradePending", ignore = true)
    @Mapping(target = "pendingUpgradeOrderId", source = "upgradePendingRazorpayOrderId")
    @Mapping(target = "pendingUpgradePlanName", ignore = true)
    @Mapping(target = "scheduledBillingCycle", source = "scheduledBillingCycle")
    @Mapping(target = "scheduledDowngradePlan", ignore = true)
    @Mapping(target = "downgradeReason", source = "downgradeReason")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    SubscriptionResponse toResponse(Subscription entity);

    default SubscriptionResponse toResponseWithPlan(Subscription entity, Plan plan) {
        SubscriptionResponse response = toResponse(entity);

        if (plan != null) {
            response.setPlanName(plan.getName());
            response.setPlanDisplayName(plan.getDisplayName());
            // Note: Pricing fields (priceMonthly, priceAnnual, currency) are set by SubscriptionService
            // after calling this mapper, using PlanService helper methods
        }

        response.setUpgradePending(entity.hasUpgradePending());

        return response;
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "planName", ignore = true)
    @Mapping(target = "planDisplayName", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "billingCycle", ignore = true)
    @Mapping(target = "trialStart", ignore = true)
    @Mapping(target = "trialEnd", ignore = true)
    @Mapping(target = "trialDaysRemaining", ignore = true)
    @Mapping(target = "currentPeriodStart", ignore = true)
    @Mapping(target = "currentPeriodEnd", ignore = true)
    @Mapping(target = "priceMonthly", ignore = true)
    @Mapping(target = "priceAnnual", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "cancelAtPeriodEnd", ignore = true)
    @Mapping(target = "upgradePending", ignore = true)
    @Mapping(target = "pendingUpgradeOrderId", ignore = true)
    @Mapping(target = "pendingUpgradePlanName", ignore = true)
    @Mapping(target = "scheduledBillingCycle", ignore = true)
    @Mapping(target = "scheduledDowngradePlan", ignore = true)
    @Mapping(target = "downgradeReason", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateFromPlan(Subscription entity, Plan plan, @MappingTarget SubscriptionResponse response);
}
