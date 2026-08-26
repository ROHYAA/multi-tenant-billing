package com.mtbs.business.payment.repository;

import com.mtbs.business.payment.entity.Payment;
import com.mtbs.shared.enums.bill.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // ── Per-invoice queries ───────────────────────────────────────────────────

    /**
     * All payments for a specific invoice.
     * Primary access pattern — used by PaymentController and
     * PaymentService.listByInvoice().
     */
    List<Payment> findAllByInvoiceId(Long invoiceId);

    /**
     * Total CONFIRMED amount collected for a specific invoice — PENDING
     * (credit-promise) payments do not count as collected.
     * Used by PaymentService to:
     *   1. Validate new payment doesn't exceed outstanding balance
     *   2. Check whether invoice is now fully paid
     * COALESCE handles the case where no payments exist yet (returns 0).
     */
    @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM Payment p
        WHERE p.invoiceId = :invoiceId
          AND p.status    = com.mtbs.shared.enums.bill.PaymentStatus.CONFIRMED
        """)
    BigDecimal sumAmountByInvoiceId(@Param("invoiceId") Long invoiceId);

    /**
     * True if this invoice has any PENDING (unconfirmed credit) payment
     * against it. Used by BillService.voidInvoice() to block voiding a
     * bill that still has a credit promise outstanding against it.
     */
    boolean existsByInvoiceIdAndStatus(Long invoiceId, PaymentStatus status);

    // ── Report queries ────────────────────────────────────────────────────────

    /**
     * Total CONFIRMED revenue collected across all invoices within a date
     * range — a PENDING credit payment is a promise, not collected cash,
     * so it must not inflate reported revenue until confirmed.
     * Revenue is measured by paid_at (actual payment date), not createdAt.
     * Used by ReportService.getRevenueReport().
     * COALESCE returns 0 when no payments fall in the period.
     */
    @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM Payment p
        WHERE p.paidAt >= :from
          AND p.paidAt <= :to
          AND p.status  = com.mtbs.shared.enums.bill.PaymentStatus.CONFIRMED
        """)
    BigDecimal sumPaymentsInPeriod(
            @Param("from") Instant from,
            @Param("to")   Instant to
    );

    /**
     * CONFIRMED revenue broken down by payment method within a date range.
     * Returns one row per method: [methodString, totalAmount]. A method's
     * PENDING (credit) amount is excluded — it isn't collected revenue yet.
     * Used by ReportService.getRevenueReport() for the method breakdown.
     *
     * Result rows are Object[] where:
     *   row[0] = String (PaymentMethod enum name, e.g. "UPI")
     *   row[1] = BigDecimal (total amount for that method)
     *
     * Only methods with at least one CONFIRMED payment in the period are returned.
     */
    @Query("""
        SELECT p.method, COALESCE(SUM(p.amount), 0)
        FROM Payment p
        WHERE p.paidAt >= :from
          AND p.paidAt <= :to
          AND p.status  = com.mtbs.shared.enums.bill.PaymentStatus.CONFIRMED
        GROUP BY p.method
        """)
    List<Object[]> sumByMethodInPeriod(
            @Param("from") Instant from,
            @Param("to")   Instant to
    );

    /**
     * All payments within a date range — ordered by paid_at descending.
     * Used internally when detailed payment list is needed for a period.
     */
    List<Payment> findAllByPaidAtBetween(Instant from, Instant to);

    // ── Payment summary queries ────────────────────────────────────────────────

    /**
     * All payments with a paidAt timestamp (successful payments).
     */
    @Query("""
        SELECT p FROM Payment p
        WHERE p.paidAt IS NOT NULL
        ORDER BY p.paidAt DESC
        """)
    List<Payment> findAllSuccessful();

    /**
     * Total count of all payments.
     */
    @Query("SELECT COUNT(p) FROM Payment p")
    long countAll();

    /**
     * Sum of all successful payment amounts.
     */
    @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM Payment p
        WHERE p.paidAt IS NOT NULL
        """)
    BigDecimal sumAllSuccessful();
}