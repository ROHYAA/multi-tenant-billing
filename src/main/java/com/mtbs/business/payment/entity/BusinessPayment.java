package com.mtbs.business.payment.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.billing.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * A payment received from a customer against a BusinessInvoice.
 *
 * Supports partial payments — multiple BusinessPayments can exist per invoice.
 * The invoice transitions to PAID when sum(amount) >= invoice.totalAmount.
 *
 * paidAt vs createdAt:
 *   - createdAt = when the record was entered into the system (audit field)
 *   - paidAt    = actual payment date (may be earlier for offline payments
 *                 recorded retroactively, e.g. "customer paid by cheque 3 days ago")
 *
 * Offline payments (BANK_TRANSFER, NETBANKING, CARD via POS):
 *   - razorpayPaymentLinkId is null
 *   - notes field carries the reference (UTR, cheque number, etc.)
 *
 * Online payments via Razorpay Payment Link:
 *   - razorpayPaymentLinkId is populated
 *   - notes may carry additional context
 *
 * CASH is intentionally not in PaymentMethod enum — tenants should
 * use BANK_TRANSFER with a note for cash entries (better audit trail).
 */
@Entity
@Table(name = "business_payments")
@SQLDelete(sql = "UPDATE business_payments SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessPayment extends AuditableEntity {

    /**
     * FK to business_invoices(id).
     * Stored as Long — not a @ManyToOne — no cascading needed.
     */
    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    /**
     * Payment method. Reuses the existing PaymentMethod enum.
     * CARD / UPI / NETBANKING / BANK_TRANSFER.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PaymentMethod method;

    /**
     * Free-text reference for offline payments.
     * e.g. "UTR 123456789", "Cheque #42 dated 2026-03-15"
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Actual payment date. May differ from createdAt for backdated entries.
     * Used as the authoritative date in revenue reports.
     */
    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    /**
     * Razorpay Payment Link ID (plink_XXXX) through which the customer paid.
     * Null for offline payments.
     */
    @Column(name = "razorpay_payment_link_id", length = 100)
    private String razorpayPaymentLinkId;
}