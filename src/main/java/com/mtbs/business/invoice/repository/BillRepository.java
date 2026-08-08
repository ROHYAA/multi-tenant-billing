package com.mtbs.business.invoice.repository;

import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.shared.enums.bill.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.mtbs.business.invoice.entity.Bill;

@Repository
public interface BillRepository extends JpaRepository<Bill, Long> {

    // ── Uniqueness ────────────────────────────────────────────────────────────

    boolean existsByInvoiceNumber(String invoiceNumber);

    Optional<Bill> findByInvoiceNumber(String invoiceNumber);

    // ── Filtered paginated listing ────────────────────────────────────────────

    /**
     * Paginated invoice list with optional customer and status filters.
     * Primary query for GET /api/business-invoices.
     * Null params are treated as "no filter" — all values match.
     */
    @Query("""
        SELECT i FROM Bill i
        WHERE (:customerId IS NULL OR i.customerId = :customerId)
          AND (:status     IS NULL OR i.status     = :status)
        ORDER BY i.createdAt DESC
        """)
    Page<Bill> findWithFilters(
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
    List<Bill> findAllByCustomerIdAndStatusNot(Long customerId, InvoiceStatus status);

    // ── Outstanding report queries ────────────────────────────────────────────

    /**
     * All non-deleted OPEN invoices ordered by due date ascending.
     * Used by ReportService.getOutstandingReport().
     */
    @Query("""
        SELECT i FROM Bill i
        WHERE i.status = com.mtbs.shared.enums.bill.InvoiceStatus.OPEN
        ORDER BY i.dueDate ASC NULLS LAST
        """)
    List<Bill> findAllOpen();

    /**
     * OPEN invoices whose due date has passed.
     * Used by outstanding report to separate current vs overdue.
     */
    @Query("""
        SELECT i FROM Bill i
        WHERE i.status   = com.mtbs.shared.enums.bill.InvoiceStatus.OPEN
          AND i.dueDate IS NOT NULL
          AND i.dueDate  < :now
        """)
    List<Bill> findAllOverdue(@Param("now") Instant now);

    // ── Date-range report queries ─────────────────────────────────────────────

    /**
     * Invoices of a specific status created within a date range.
     * Used by ReportService.getRevenueReport() to count paid invoices.
     */
    @Query("""
        SELECT i FROM Bill i
        WHERE i.status     = :status
          AND i.createdAt >= :from
          AND i.createdAt <= :to
        ORDER BY i.createdAt DESC
        """)
    List<Bill> findAllByStatusAndCreatedAtBetween(
            @Param("status") InvoiceStatus status,
            @Param("from")   Instant from,
            @Param("to")     Instant to
    );

    /**
     * All invoices created within a date range (any status).
     * Used by ReportService.getMonthlySummary().
     */
    @Query("""
        SELECT i FROM Bill i
        WHERE i.createdAt >= :from
          AND i.createdAt <= :to
        """)
    List<Bill> findAllByCreatedAtBetween(
            @Param("from") Instant from,
            @Param("to")   Instant to
    );

    // ── Sequence number generation ────────────────────────────────────────────

    /**
     * Total count of all non-deleted invoices (all statuses).
     * Used by BillService.generateInvoiceNumber() to derive
     * the next sequence number. Monotonically increasing.
     */
    @Query("SELECT COUNT(i) FROM Bill i")
    long countAllIncludingVoid();

    // ── Revenue summary queries ────────────────────────────────────────────────────────────

    /**
     * PAID invoices within a date range.
     */
    @Query("""
        SELECT i FROM Bill i
        WHERE i.status     = com.mtbs.shared.enums.bill.InvoiceStatus.PAID
          AND i.paidAt    >= :from
          AND i.paidAt    <= :to
        ORDER BY i.paidAt DESC
        """)
    List<Bill> findAllPaidBetween(
            @Param("from") Instant from,
            @Param("to")   Instant to
    );

    /**
     * All PAID invoices.
     */
    @Query("""
        SELECT i FROM Bill i
        WHERE i.status = com.mtbs.shared.enums.bill.InvoiceStatus.PAID
        ORDER BY i.paidAt DESC
        """)
    List<Bill> findAllPaid();
}