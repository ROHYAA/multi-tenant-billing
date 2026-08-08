package com.mtbs.business.payment.repository;

import com.mtbs.business.payment.entity.BusinessPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface BusinessPaymentRepository extends JpaRepository<BusinessPayment, Long> {

    // ── Per-invoice queries ───────────────────────────────────────────────────

    /**
     * All payments for a specific invoice.
     * Primary access pattern — used by BusinessPaymentController and
     * BusinessPaymentService.listByInvoice().
     */
    List<BusinessPayment> findAllByInvoiceId(Long invoiceId);

    /**
     * Total amount collected for a specific invoice.
     * Used by BusinessPaymentService to:
     *   1. Validate new payment doesn't exceed outstanding balance
     *   2. Check whether invoice is now fully paid
     * COALESCE handles the case where no payments exist yet (returns 0).
     */
    @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM BusinessPayment p
        WHERE p.invoiceId = :invoiceId
        """)
    BigDecimal sumAmountByInvoiceId(@Param("invoiceId") Long invoiceId);

    // ── Report queries ────────────────────────────────────────────────────────

    /**
     * Total revenue collected across all invoices within a date range.
     * Revenue is measured by paid_at (actual payment date), not createdAt.
     * Used by BusinessReportService.getRevenueReport().
     * COALESCE returns 0 when no payments fall in the period.
     */
    @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM BusinessPayment p
        WHERE p.paidAt >= :from
          AND p.paidAt <= :to
        """)
    BigDecimal sumPaymentsInPeriod(
            @Param("from") Instant from,
            @Param("to")   Instant to
    );

    /**
     * Revenue broken down by payment method within a date range.
     * Returns one row per method: [methodString, totalAmount].
     * Used by BusinessReportService.getRevenueReport() for the method breakdown.
     *
     * Result rows are Object[] where:
     *   row[0] = String (PaymentMethod enum name, e.g. "UPI")
     *   row[1] = BigDecimal (total amount for that method)
     *
     * Only methods with at least one payment in the period are returned.
     */
    @Query("""
        SELECT p.method, COALESCE(SUM(p.amount), 0)
        FROM BusinessPayment p
        WHERE p.paidAt >= :from
          AND p.paidAt <= :to
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
    List<BusinessPayment> findAllByPaidAtBetween(Instant from, Instant to);

    // ── Payment summary queries ────────────────────────────────────────────────

    /**
     * All payments with a paidAt timestamp (successful payments).
     */
    @Query("""
        SELECT p FROM BusinessPayment p
        WHERE p.paidAt IS NOT NULL
        ORDER BY p.paidAt DESC
        """)
    List<BusinessPayment> findAllSuccessful();

    /**
     * Total count of all payments.
     */
    @Query("SELECT COUNT(p) FROM BusinessPayment p")
    long countAll();

    /**
     * Sum of all successful payment amounts.
     */
    @Query("""
        SELECT COALESCE(SUM(p.amount), 0)
        FROM BusinessPayment p
        WHERE p.paidAt IS NOT NULL
        """)
    BigDecimal sumAllSuccessful();
}