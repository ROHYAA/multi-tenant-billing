package com.mtbs.billing.dto;

import jakarta.validation.constraints.Min;
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
public class RefundRequest {

    /**
     * Amount to refund in INR (whole rupees, not paise).
     * null or 0 → full refund.
     * Positive value → partial refund up to original payment amount.
     */
    @Min(value = 0, message = "Refund amount must be zero (full refund) or a positive value")
    private Long amount;

    private String reason;
}