package com.mtbs.billing.dto;

import com.mtbs.shared.enums.billing.InvoiceStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceResponse {

    private Long id;
    private Long subscriptionId;
    private String invoiceNumber;
    private InvoiceStatus status;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private String currency;
    private Instant dueDate;
    private Instant paidAt;
    private Instant billingPeriodStart;
    private Instant billingPeriodEnd;
    private List<InvoiceLineItemResponse> lineItems;
    private Instant createdAt;
}
