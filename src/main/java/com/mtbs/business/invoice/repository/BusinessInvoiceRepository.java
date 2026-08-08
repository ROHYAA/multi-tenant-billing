package com.mtbs.business.invoice.repository;

import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.mtbs.business.invoice.entity.BusinessInvoice;

@Repository
public interface BusinessInvoiceRepository extends JpaRepository<BusinessInvoice, Long> {

    // ── Uniqueness ────────────────────────────────────────────────────────────

    boolean existsByInvoiceNumber(String invoiceNumber);

    Optional<BusinessInvoice> findByInvoiceNumber(String invoiceNumber);

    // ── Filtered paginated listing ────────────────────────────────────────────

    /**
     * Paginated invoice list with optional customer and status filters.
     * Primary query for GET /api/business-invoices.
     * Null params are treated as "no filter" — all values match.
     */
    @Query("""
        SELECT i FROM BusinessInvoice i
        WHERE (:customerId IS NULL OR i.customerId = :customerId)
          AND (:status     IS NULL OR i.status     = :status)
        ORDER BY i.createdAt DESC
        """)
    Page<BusinessInvoice> findWithFilters(
            @Param("customerId") Long customerId,
            @Param("status")     InvoiceStatus status,
            Pageable pageable
    );

    // ── Customer relationship guards ──────────────────────────────────────────

    /**
     * All invoices for a customer that are NOT in a given status.
     * Used by CustomerService.delete() to block deletion when open/paid invoices exist.
     * Example: findAllByCustomerIdAndStatusNot(id, InvoiceStatus.VOID)
     */
    List<BusinessInvoice> findAllByCustomerIdAndStatusNot(Long customerId, InvoiceStatus status);

    // ── Outstanding report queries ────────────────────────────────────────────

    /**
     * All non-deleted OPEN invoices ordered by due date ascending.
     * Used by BusinessReportService.getOutstandingReport().
     */
    @Query("""
        SELECT i FROM BusinessInvoice i
        WHERE i.status = com.mtbs.shared.enums.billing.InvoiceStatus.OPEN
        ORDER BY i.dueDate ASC NULLS LAST
        """)
    List<BusinessInvoice> findAllOpen();

    /**
     * OPEN invoices whose due date has passed.
     * Used by outstanding report to separate current vs overdue.
     */
    @Query("""
        SELECT i FROM BusinessInvoice i
        WHERE i.status   = com.mtbs.shared.enums.billing.InvoiceStatus.OPEN
          AND i.dueDate IS NOT NULL
          AND i.dueDate  < :now
        """)
    List<BusinessInvoice> findAllOverdue(@Param("now") Instant now);

    // ── Date-range report queries ─────────────────────────────────────────────

    /**
     * Invoices of a specific status created within a date range.
     * Used by BusinessReportService.getRevenueReport() to count paid invoices.
     */
    @Query("""
        SELECT i FROM BusinessInvoice i
        WHERE i.status     = :status
          AND i.createdAt >= :from
          AND i.createdAt <= :to
        ORDER BY i.createdAt DESC
        """)
    List<BusinessInvoice> findAllByStatusAndCreatedAtBetween(
            @Param("status") InvoiceStatus status,
            @Param("from")   Instant from,
            @Param("to")     Instant to
    );

    /**
     * All invoices created within a date range (any status).
     * Used by BusinessReportService.getMonthlySummary().
     */
    @Query("""
        SELECT i FROM BusinessInvoice i
        WHERE i.createdAt >= :from
          AND i.createdAt <= :to
        """)
    List<BusinessInvoice> findAllByCreatedAtBetween(
            @Param("from") Instant from,
            @Param("to")   Instant to
    );

    // ── Sequence number generation ────────────────────────────────────────────

    /**
     * Total count of all non-deleted invoices (all statuses).
     * Used by BusinessInvoiceService.generateInvoiceNumber() to derive
     * the next sequence number. Monotonically increasing.
     */
    @Query("SELECT COUNT(i) FROM BusinessInvoice i")
    long countAllIncludingVoid();

    // ── Revenue summary queries ────────────────────────────────────────────────────────────

    /**
     * PAID invoices within a date range.
     */
    @Query("""
        SELECT i FROM BusinessInvoice i
        WHERE i.status     = com.mtbs.shared.enums.billing.InvoiceStatus.PAID
          AND i.paidAt    >= :from
          AND i.paidAt    <= :to
        ORDER BY i.paidAt DESC
        """)
    List<BusinessInvoice> findAllPaidBetween(
            @Param("from") Instant from,
            @Param("to")   Instant to
    );

    /**
     * All PAID invoices.
     */
    @Query("""
        SELECT i FROM BusinessInvoice i
        WHERE i.status = com.mtbs.shared.enums.billing.InvoiceStatus.PAID
        ORDER BY i.paidAt DESC
        """)
    List<BusinessInvoice> findAllPaid();
}