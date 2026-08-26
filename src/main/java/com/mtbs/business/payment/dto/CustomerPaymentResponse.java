package com.mtbs.business.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Result of PaymentService.recordForCustomer() — one customer payment can
 * turn into several Payment rows (one per bill it touched, FIFO oldest-first).
 * All rows share paymentGroupId so they can be shown/receipted together.
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerPaymentResponse {

    private UUID paymentGroupId;

    /** The amount the caller asked to record — always fully allocated (overpayment is rejected up front). */
    private BigDecimal totalAmount;

    /** Bills that reached PAID as a direct result of this payment. */
    private int billsCompleted;

    /** One row per bill this payment touched, oldest-first. */
    private List<PaymentResponse> payments;
}
