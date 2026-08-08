package com.mtbs.business.payment.dto;
 
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.mtbs.shared.enums.billing.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
import java.time.Instant;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecordPaymentRequest {
 
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    private BigDecimal amount;
 
    @NotNull(message = "Payment method is required")
    private PaymentMethod method;
 
    /**
     * Free-text reference for offline payments.
     * e.g. "UTR 123456789", "Cheque #42 dated 2026-03-15"
     */
    private String notes;
 
    /**
     * Actual payment date. Optional — defaults to NOW().
     * Set this when recording an offline payment that happened in the past.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private Instant paidAt = Instant.now();
}