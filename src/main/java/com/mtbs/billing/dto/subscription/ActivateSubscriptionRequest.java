package com.mtbs.billing.dto.subscription;

import com.mtbs.shared.enums.billing.BillingCycle;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivateSubscriptionRequest {

    @NotNull(message = "Plan ID is required")
    private Long planId;

    @NotNull(message = "Billing cycle is required")
    private BillingCycle billingCycle;
}
