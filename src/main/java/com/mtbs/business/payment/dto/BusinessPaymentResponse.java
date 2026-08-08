package com.mtbs.business.payment.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.billing.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
import java.time.Instant;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessPaymentResponse {
 
    private Long          id;
    private Long          invoiceId;
    private BigDecimal    amount;
    private String        currency;
    private PaymentMethod method;
 
    /** Free-text reference: UTR, cheque number, etc. */
    private String notes;
 
    /** Actual payment date — may differ from createdAt for backdated entries. */
    private Instant paidAt;
 
    /**
     * Razorpay Payment Link ID used for this payment.
     * Null for offline payments.
     */
    private String razorpayPaymentLinkId;
 
    /** When this record was entered into the system. */
    private Instant createdAt;
}