package com.mtbs.business.invoice.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessInvoiceResponse {
 
    private Long   id;
 
    /** Format: BINV-{tenantId}-{YYYYMM}-{seq}. */
    private String invoiceNumber;
 
    private Long   customerId;
    private String customerName;
    private String customerEmail;
 
    private InvoiceStatus status;
 
    // ── Financials ────────────────────────────────────────────────────────────
 
    /** Sum of (unitPrice × qty) — excludes tax. */
    private BigDecimal subtotal;
 
    /** Sum of per-item tax amounts. */
    private BigDecimal taxAmount;
 
    /** subtotal + taxAmount — the final amount owed by the customer. */
    private BigDecimal totalAmount;
 
    private String currency;
 
    // ── Optional fields ───────────────────────────────────────────────────────
 
    private String notes;
 
    /** Set when the invoice is finalized. */
    private Instant dueDate;
 
    /** Set when the invoice transitions to PAID. */
    private Instant paidAt;
 
    /**
     * Razorpay Payment Link ID. Null until
     * POST /api/business-invoices/{id}/payment-link is called.
     */
    private String razorpayPaymentLinkId;
 
    // ── Line items ────────────────────────────────────────────────────────────
 
    private List<BusinessInvoiceItemResponse> items;
 
    private Instant createdAt;
    private Instant updatedAt;
}