package com.mtbs.tenant.service.onboarding;

import com.mtbs.tenant.dto.onboarding.OnboardingStatusResponse;
import com.mtbs.tenant.dto.onboarding.OnboardingStep1Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStep2Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStep3PaymentResponse;
import com.mtbs.tenant.dto.onboarding.OnboardingStep3Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStepResponse;
import com.mtbs.tenant.entity.TenantOnboarding;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.tenant.enums.KycStatus;
import com.mtbs.tenant.enums.OnboardingPaymentStatus;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.exception.TenantException;
import com.mtbs.tenant.repository.PlanRepository;
import com.mtbs.tenant.repository.TenantOnboardingRepository;
import com.mtbs.tenant.repository.TenantRepository;
import com.mtbs.tenant.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the 3-step tenant onboarding flow.
 *
 * ALL operations here are in the PUBLIC schema — TenantContext is NOT set
 * until step 3 completion when the subscription is created in the tenant schema.
 *
 * Write operations are delegated to OnboardingScopedService to keep
 * @Transactional within a proper Spring proxy boundary.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingService {

    private final TenantRepository tenantRepository;
    private final TenantOnboardingRepository onboardingRepository;
    private final PlanRepository planRepository;
    private final PlanService planService;
    private final OnboardingScopedService onboardingScopedService;
    private final OnboardingCompletionService onboardingCompletionService;

    // ── Step 1 ────────────────────────────────────────────────────────────────

    public OnboardingStepResponse saveStep1(Long tenantId, OnboardingStep1Request request) {
        log.info("Saving onboarding step 1 for tenantId={}", tenantId);

        Tenant tenant = requireOnboardingTenant(tenantId);

        // Slug uniqueness check — exclude the tenant's own record
        TenantOnboarding existing = requireOnboardingRecord(tenantId);
        if (!request.getSlug().equals(existing.getSlug())) {
            if (onboardingRepository.existsBySlugAndTenantIdNot(request.getSlug(), tenantId)) {
                throw TenantException.slugAlreadyTaken(request.getSlug());
            }
        }

        onboardingScopedService.persistStep1(existing, request, tenant);

        return OnboardingStepResponse.builder()
                .stepCompleted(1)
                .nextStep(2)
                .onboardingComplete(false)
                .message("Business details saved successfully")
                .build();
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    public OnboardingStepResponse saveStep2(Long tenantId, OnboardingStep2Request request) {
        log.info("Saving onboarding step 2 (KYC) for tenantId={}", tenantId);

        requireOnboardingTenant(tenantId);
        TenantOnboarding onboarding = requireOnboardingRecord(tenantId);

        if (onboarding.getCompanyName() == null) {
            throw TenantException.stepOutOfOrder(1, 2);
        }

        onboardingScopedService.persistStep2(onboarding, request);

        return OnboardingStepResponse.builder()
                .stepCompleted(2)
                .nextStep(3)
                .onboardingComplete(false)
                .message("KYC details submitted successfully")
                .build();
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    public OnboardingStep3PaymentResponse saveStep3(Long tenantId, OnboardingStep3Request request) {
        log.info("Saving onboarding step 3 (plan) for tenantId={}, planId={}", tenantId, request.getPlanId());

        requireOnboardingTenant(tenantId);
        TenantOnboarding onboarding = requireOnboardingRecord(tenantId);

        if (onboarding.getCompletedAt() != null) {
            log.warn("Onboarding already completed for tenantId={}", tenantId);
            return OnboardingStep3PaymentResponse.builder()
                    .paymentRequired(false)
                    .tenantId(tenantId)
                    .onboardingComplete(true)
                    .message("Onboarding already completed")
                    .currentStep(0)
                    .build();
        }

        if (onboarding.getKycStatus() == KycStatus.PENDING) {
            throw TenantException.stepOutOfOrder(2, 3);
        }

        Plan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> ResourceException.notFound("Plan", request.getPlanId()));

        if (onboarding.getSelectedPlanId() != null && onboarding.getPaymentStatus() == OnboardingPaymentStatus.PAID) {
            log.warn("Payment already completed for tenantId={}", tenantId);
            return OnboardingStep3PaymentResponse.builder()
                    .paymentRequired(false)
                    .tenantId(tenantId)
                    .planId(plan.getId())
                    .planName(plan.getName())
                    .onboardingComplete(true)
                    .message("Onboarding already completed")
                    .currentStep(0)
                    .build();
        }

        boolean isPaidPlan = !isFreePlan(plan);

        if (isPaidPlan) {
            return handlePaidPlanSelection(tenantId, onboarding, plan, request);
        } else {
            return handleFreePlanSelection(tenantId, onboarding, plan, request);
        }
    }

    private boolean isFreePlan(Plan plan) {
        return "FREE".equalsIgnoreCase(plan.getName());
    }

    private OnboardingStep3PaymentResponse handleFreePlanSelection(
            Long tenantId, TenantOnboarding onboarding, Plan plan, OnboardingStep3Request request) {
        
        onboardingScopedService.persistStep3(onboarding, request);
        
        onboardingCompletionService.completeOnboarding(tenantId, plan, request.getBillingCycle());

        return OnboardingStep3PaymentResponse.builder()
                .paymentRequired(false)
                .tenantId(tenantId)
                .planId(plan.getId())
                .planName(plan.getName())
                .billingCycle(request.getBillingCycle())
                .onboardingComplete(true)
                .message("Onboarding complete. Welcome!")
                .currentStep(0)
                .build();
    }

    private OnboardingStep3PaymentResponse handlePaidPlanSelection(
            Long tenantId, TenantOnboarding onboarding, Plan plan, OnboardingStep3Request request) {
        
        onboardingScopedService.persistStep3(onboarding, request);

        onboardingCompletionService.completeOnboarding(tenantId, plan, request.getBillingCycle());

        int trialDays = planService.getTrialDaysForPlan(plan.getId(), BillingCycle.MONTHLY);
        
        log.info("Onboarding completed with trial for tenantId={}, plan={}, trialDays={}", 
                tenantId, plan.getName(), trialDays);

        return OnboardingStep3PaymentResponse.builder()
                .paymentRequired(false)
                .tenantId(tenantId)
                .planId(plan.getId())
                .planName(plan.getName())
                .billingCycle(request.getBillingCycle())
                .trialDays(trialDays)
                .onboardingComplete(true)
                .message(trialDays > 0 
                        ? "Onboarding complete! You have " + trialDays + " days free trial."
                        : "Onboarding complete!")
                .currentStep(0)
                .build();
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public OnboardingStatusResponse getStatus(Long tenantId) {
        log.info("Fetching onboarding status for tenantId={}", tenantId);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ResourceException.notFound("Tenant", tenantId));

        TenantOnboarding onboarding = requireOnboardingRecord(tenantId);

        List<Integer> completed = resolveCompletedSteps(onboarding);
        boolean done = tenant.getStatus() == Status.ACTIVE;

        OnboardingStatusResponse.Step1Data step1 = null;
        if (onboarding.getCompanyName() != null) {
            step1 = OnboardingStatusResponse.Step1Data.builder()
                    .companyName(onboarding.getCompanyName())
                    .slug(onboarding.getSlug())
                    .industry(onboarding.getIndustry())
                    .phone(onboarding.getPhone())
                    .timezone(onboarding.getTimezone())
                    .teamSize(onboarding.getTeamSize())
                    .useCase(onboarding.getUseCase())
                    .build();
        }

        OnboardingStatusResponse.Step2Summary step2 = null;
        if (onboarding.getBusinessType() != null) {
            step2 = OnboardingStatusResponse.Step2Summary.builder()
                    .businessType(onboarding.getBusinessType().name())
                    .registrationNumber(onboarding.getRegistrationNumber())
                    .kycStatus(onboarding.getKycStatus())
                    .build();
        }

        OnboardingStatusResponse.Step3Summary step3 = null;
        if (onboarding.getSelectedPlanId() != null) {
            Plan plan = planRepository.findById(onboarding.getSelectedPlanId()).orElse(null);
            String planName = plan != null ? plan.getName() : "UNKNOWN";
            Long amountPaise = null;
            
            if (plan != null && onboarding.getSelectedBillingCycle() != null) {
                amountPaise = onboarding.getSelectedBillingCycle() == BillingCycle.MONTHLY
                        ? planService.getPriceMonthly(plan.getId()).multiply(java.math.BigDecimal.valueOf(100)).longValue()
                        : planService.getPriceAnnual(plan.getId()).multiply(java.math.BigDecimal.valueOf(100)).longValue();
            }
            
            step3 = OnboardingStatusResponse.Step3Summary.builder()
                    .planId(onboarding.getSelectedPlanId())
                    .planName(planName)
                    .billingCycle(onboarding.getSelectedBillingCycle() != null
                            ? onboarding.getSelectedBillingCycle().name() : null)
                    .completedAt(onboarding.getCompletedAt())
                    .paymentStatus(onboarding.getPaymentStatus())
                    .razorpayOrderId(onboarding.getRazorpayOrderId())
                    .amountPaise(amountPaise)
                    .build();
        }

        return OnboardingStatusResponse.builder()
                .tenantId(tenantId)
                .currentStep(tenant.getOnboardingStep())
                .onboardingComplete(done)
                .completedSteps(completed)
                .kycStatus(onboarding.getKycStatus())
                .step1(step1)
                .step2(step2)
                .step3(step3)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Tenant requireOnboardingTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ResourceException.notFound("Tenant", tenantId));
        if (tenant.getStatus() == Status.ACTIVE) {
            throw TenantException.notInOnboarding(tenantId);
        }
        return tenant;
    }

    private TenantOnboarding requireOnboardingRecord(Long tenantId) {
        return onboardingRepository.findByTenantId(tenantId)
                .orElseThrow(() -> ResourceException.notFound("OnboardingRecord", tenantId));
    }

    private List<Integer> resolveCompletedSteps(TenantOnboarding o) {
        List<Integer> steps = new ArrayList<>();
        if (o.getCompanyName() != null)    steps.add(1);
        if (o.getBusinessType() != null)   steps.add(2);
        if (o.getSelectedPlanId() != null && o.getPaymentStatus() == OnboardingPaymentStatus.PAID) {
            steps.add(3);
        } else if (o.getSelectedPlanId() != null && o.getPaymentStatus() == null) {
            steps.add(3);
        }
        return steps;
    }
}