package com.mtbs.tenant.dto.plan;

import com.mtbs.shared.enums.billing.BillingCycle;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Request DTO for creating/updating plan pricing.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePlanPricingRequest {

    @NotNull(message = "Billing cycle is required")
    private BillingCycle billingCycle;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be non-negative")
    private BigDecimal price;

    @Builder.Default
    private String currency = "INR";

    @Builder.Default
    @Min(value = 0, message = "Trial days must be non-negative")
    private Integer trialDays = 0;

    @Builder.Default
    private Boolean isDefault = false;
}
