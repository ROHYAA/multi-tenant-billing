package com.mtbs.business.payment.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.mtbs.shared.enums.bill.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One payment from a customer, to be allocated FIFO (oldest bill first)
 * across all of that customer's OPEN bills — see
 * PaymentService.recordForCustomer(). Unlike RecordPaymentRequest, there is
 * no invoiceId: the caller states an amount for the customer, not a bill.
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecordCustomerPaymentRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    private BigDecimal amount;

    @NotNull(message = "Payment method is required")
    private PaymentMethod method;

    private String notes;

    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private Instant paidAt = Instant.now();
}
