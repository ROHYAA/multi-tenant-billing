package com.mtbs.business.payment.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.bill.PaymentMethod;
import com.mtbs.shared.enums.bill.PaymentStatus;
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
import java.util.UUID;

/**
 * A payment received from a customer against a Bill.
 *
 * Supports partial payments — multiple Payments can exist per invoice.
 * The invoice transitions to PAID when sum(amount) >= invoice.totalAmount.
 *
 * paidAt vs createdAt:
 *   - createdAt = when the record was entered into the system (audit field)
 *   - paidAt    = actual payment date (may be earlier for offline payments
 *                 recorded retroactively, e.g. "customer paid by cheque 3 days ago")
 *
 * All payments are recorded manually (cash/card/UPI/bank transfer at the
 * counter) — notes field carries any reference (UTR, cheque number, etc.).
 *
 * Credit payments (method = CREDIT) are inserted PENDING — see
 * PaymentStatus — and only count toward the invoice's outstanding balance
 * once confirmed.
 */
@Entity
@Table(name = "payments")
@SQLDelete(sql = "UPDATE payments SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment extends AuditableEntity {

    /**
     * FK to bills(id).
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
     * CASH / CARD / UPI / NETBANKING / BANK_TRANSFER / CREDIT.
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
     * PENDING for a Credit payment (a promise, not collected cash) until
     * confirmPayment() flips it to CONFIRMED. Every other method is
     * CONFIRMED on insert. Only CONFIRMED payments count toward an
     * invoice's outstanding balance.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.CONFIRMED;

    /**
     * Set only when this row was one of several created by a single
     * customer-level FIFO payment (PaymentService.recordForCustomer) that
     * spanned multiple bills — lets those rows be displayed/receipted
     * together. Null for an ordinary single-invoice payment.
     */
    @Column(name = "payment_group_id")
    private UUID paymentGroupId;
}