package com.mtbs.business.invoice.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A manual invoice raised by the tenant for their customer.
 *
 * Lifecycle:
 *   DRAFT  → line items can be added/removed, totals recalculated
 *   OPEN   → finalized, due date set, PDF generated, sent to customer
 *   PAID   → one or more BusinessPayments have covered the total_amount
 *   VOID   → cancelled before payment (cannot void a PAID invoice)
 *
 * Intentionally SEPARATE from the platform Invoice entity (which records
 * what the tenant owes the platform for their subscription).
 *
 * invoiceNumber format: BINV-{tenantId}-{YYYYMM}-{seq}
 * "B" prefix distinguishes from platform INV-* numbers.
 *
 * Totals are stored explicitly (not computed on read) for audit immutability.
 * Recalculation happens only in BusinessInvoiceService when line items change,
 * and only while the invoice is in DRAFT status.
 */
@Entity
@Table(name = "business_invoices")
@SQLDelete(sql = "UPDATE business_invoices SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessInvoice extends AuditableEntity {

    @Column(name = "invoice_number", nullable = false, unique = true, length = 50)
    private String invoiceNumber;

    /**
     * FK to customers(id) — within this tenant schema.
     * Stored as a plain Long (not a @ManyToOne) to avoid cross-entity lazy
     * loading issues. Customer details are fetched explicitly when needed.
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    // ── Financials ────────────────────────────────────────────────────────────

    /** Sum of (unit_price × quantity) across all line items. Excludes tax. */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    /** Sum of per-line-item tax amounts. */
    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** subtotal + taxAmount. The final amount the customer owes. */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    // ── Optional fields ───────────────────────────────────────────────────────

    /** Free-text note printed at the bottom of the PDF invoice. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Set when the invoice is finalized. +N days from finalization date. */
    @Column(name = "due_date")
    private Instant dueDate;

    /** Set when the invoice transitions to PAID status. */
    @Column(name = "paid_at")
    private Instant paidAt;

    /** URL of the generated PDF. Null until the PDF is generated. */
    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    /**
     * Razorpay Payment Link ID (plink_XXXX).
     * Created on demand via POST /api/business-invoices/{id}/payment-link.
     * Null until the tenant generates a payment link.
     */
    @Column(name = "razorpay_payment_link_id", length = 100)
    private String razorpayPaymentLinkId;

    // ── Relationships ─────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BusinessInvoiceItem> items = new ArrayList<>();
}