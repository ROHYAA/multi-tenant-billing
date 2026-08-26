package com.mtbs.business.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A customer's total outstanding balance plus the ordered (oldest-first,
 * FIFO) per-bill breakdown a customer-level payment would be applied
 * against — lets the "Record Payment" UI show a live preview before
 * submitting. Backed by PaymentService.getCustomerOutstanding().
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerOutstandingResponse {

    private Long customerId;

    /** Sum of every OPEN bill's outstanding (CONFIRMED-paid-only) amount for this customer. */
    private BigDecimal totalOutstanding;

    /** Oldest-first — the order a customer-level FIFO payment would settle these in. */
    private List<BillOutstandingItem> bills;

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BillOutstandingItem {
        private Long invoiceId;
        private String invoiceNumber;
        private BigDecimal totalAmount;
        private BigDecimal outstandingAmount;
        private Instant createdAt;
        private Instant dueDate;
    }
}
