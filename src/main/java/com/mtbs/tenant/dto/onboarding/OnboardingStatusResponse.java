package com.mtbs.tenant.dto.onboarding;

import com.mtbs.tenant.enums.KycStatus;
import com.mtbs.tenant.enums.OnboardingPaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingStatusResponse {

    private Long tenantId;
    private int currentStep;           // 0 = not started, 1/2/3 = step completed
    private boolean onboardingComplete;
    private List<Integer> completedSteps;
    private KycStatus kycStatus;

    // Resume data — populated only for completed steps so the UI can pre-fill
    private Step1Data step1;
    private Step2Summary step2;
    private Step3Summary step3;

    // ── Nested summaries ──────────────────────────────────────────────────────

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step1Data {
        private String companyName;
        private String slug;
        private String industry;
        private String phone;
        private String timezone;
        private String teamSize;
        private String useCase;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step2Summary {
        private String businessType;
        private String registrationNumber;
        private KycStatus kycStatus;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Step3Summary {
        private Long planId;
        private String planName;
        private String billingCycle;
        private Instant completedAt;
        
        // Payment-related fields (for paid plans)
        private OnboardingPaymentStatus paymentStatus;
        private String razorpayOrderId;
        private Long amountPaise;
    }
}