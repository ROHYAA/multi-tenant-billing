package com.mtbs.tenant.dto.onboarding;

import com.mtbs.shared.enums.billing.BillingCycle;
import jakarta.validation.constraints.NotNull;
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
public class OnboardingStep3Request {

    @NotNull(message = "Plan ID is required")
    private Long planId;

    @NotNull(message = "Billing cycle is required")
    private BillingCycle billingCycle;

    /**
     * Optional — required only for paid plans.
     * For FREE plan this field is ignored.
     * Value is a Razorpay payment method token or card token.
     */
    private String paymentMethodToken;

    /**
     * Optional payment method ID for saved cards (future use).
     */
    private String paymentMethodId;
}