package com.mtbs.business.report.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.billing.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RevenueReportResponse {
 
    /** Period start as provided in the request. */
    private Instant from;
 
    /** Period end as provided in the request. */
    private Instant to;
 
    /** Sum of all business_payments.amount where paid_at is within the period. */
    private BigDecimal totalRevenue;
 
    /** Count of invoices that transitioned to PAID within the period. */
    private int paidInvoiceCount;
 
    /** totalRevenue / paidInvoiceCount. Zero if no paid invoices. */
    private BigDecimal averageInvoiceValue;
 
    /**
     * Revenue broken down by payment method.
     * Keys: CARD, UPI, NETBANKING, BANK_TRANSFER.
     * Only methods with payments in the period are included.
     */
    private Map<PaymentMethod, BigDecimal> revenueByMethod;
}