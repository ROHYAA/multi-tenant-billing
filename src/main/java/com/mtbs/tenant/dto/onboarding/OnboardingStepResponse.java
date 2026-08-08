package com.mtbs.tenant.dto.onboarding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingStepResponse {

    private int stepCompleted;
    private int nextStep;           // 0 means onboarding is done
    private boolean onboardingComplete;
    private String message;
}