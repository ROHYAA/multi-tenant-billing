// ─────────────────────────────────────────────────────────────────────────────
// FILE: com/mtbs/service/business/BusinessReportService.java
// ─────────────────────────────────────────────────────────────────────────────
package com.mtbs.business.report.service;

import com.mtbs.business.report.dto.MonthlyReportRow;
import com.mtbs.business.report.dto.OutstandingReportResponse;
import com.mtbs.business.report.dto.RevenueReportResponse;
import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.shared.enums.billing.PaymentMethod;
import com.mtbs.business.invoice.repository.BusinessInvoiceRepository;
import com.mtbs.business.payment.repository.BusinessPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessReportService {

    private final BusinessInvoiceRepository invoiceRepository;
    private final BusinessPaymentRepository paymentRepository;

    // ── Revenue report ────────────────────────────────────────────────────────

    /**
     * Returns total revenue collected in a date range, broken down by payment method.
     * Revenue = sum of business_payments.amount where paid_at BETWEEN from AND to.
     * Uses actual payment dates (paid_at), not invoice dates.
     */
    @Transactional(readOnly = true)
    public RevenueReportResponse getRevenueReport(Instant from, Instant to) {
        log.info("Revenue report: from={}, to={}", from, to);

        BigDecimal totalRevenue = paymentRepository.sumPaymentsInPeriod(from, to);
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;

        // Count paid invoices in period
        List<BusinessInvoice> paidInvoices = invoiceRepository
                .findAllByStatusAndCreatedAtBetween(InvoiceStatus.PAID, from, to);

        BigDecimal avgInvoiceValue = BigDecimal.ZERO;
        if (!paidInvoices.isEmpty()) {
            BigDecimal totalInvoiceValue = paidInvoices.stream()
                    .map(BusinessInvoice::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            avgInvoiceValue = totalInvoiceValue
                    .divide(BigDecimal.valueOf(paidInvoices.size()), 2, RoundingMode.HALF_UP);
        }

        // Revenue breakdown by payment method
        // row[0] is PaymentMethod enum (Hibernate hydrates @Enumerated fields as the Java type)
        // row[1] is BigDecimal (SUM result)
        Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
        List<Object[]> methodBreakdown = paymentRepository.sumByMethodInPeriod(from, to);
        for (Object[] row : methodBreakdown) {
            if (row[0] == null) continue;

            PaymentMethod method;
            if (row[0] instanceof PaymentMethod pm) {
                // Hibernate returned the enum directly (normal case for @Enumerated fields)
                method = pm;
            } else {
                // Fallback: native query or String column — parse from String
                try {
                    method = PaymentMethod.valueOf(row[0].toString());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown payment method in report: {}", row[0]);
                    continue;
                }
            }

            BigDecimal amount = row[1] instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
            byMethod.put(method, amount);
        }

        return RevenueReportResponse.builder()
                .from(from)
                .to(to)
                .totalRevenue(totalRevenue)
                .paidInvoiceCount(paidInvoices.size())
                .averageInvoiceValue(avgInvoiceValue)
                .revenueByMethod(byMethod)
                .build();
    }

    // ── Outstanding report ────────────────────────────────────────────────────

    /**
     * Returns all OPEN invoices split into current (not yet due) and overdue.
     */
    @Transactional(readOnly = true)
    public OutstandingReportResponse getOutstandingReport() {
        log.info("Outstanding report requested");

        Instant now              = Instant.now();
        List<BusinessInvoice> allOpen  = invoiceRepository.findAllOpen();
        List<BusinessInvoice> overdue  = invoiceRepository.findAllOverdue(now);

        BigDecimal overdueAmount  = sum(overdue);
        BigDecimal currentAmount  = sum(allOpen).subtract(overdueAmount).max(BigDecimal.ZERO);
        BigDecimal totalOutstanding = sum(allOpen);

        List<OutstandingReportResponse.OutstandingItem> items = new ArrayList<>();
        for (BusinessInvoice invoice : allOpen) {
            BigDecimal paid        = paymentRepository.sumAmountByInvoiceId(invoice.getId());
            BigDecimal outstanding = invoice.getTotalAmount()
                    .subtract(paid != null ? paid : BigDecimal.ZERO)
                    .max(BigDecimal.ZERO);

            boolean isOverdue = invoice.getDueDate() != null
                    && invoice.getDueDate().isBefore(now);

            items.add(OutstandingReportResponse.OutstandingItem.builder()
                    .invoiceId(invoice.getId())
                    .invoiceNumber(invoice.getInvoiceNumber())
                    .customerId(invoice.getCustomerId())
                    .totalAmount(invoice.getTotalAmount())
                    .outstandingAmount(outstanding)
                    .dueDate(invoice.getDueDate())
                    .overdue(isOverdue)
                    .build());
        }

        return OutstandingReportResponse.builder()
                .totalOutstanding(totalOutstanding)
                .overdueAmount(overdueAmount)
                .overdueCount(overdue.size())
                .currentAmount(currentAmount)
                .currentCount(allOpen.size() - overdue.size())
                .items(items)
                .build();
    }

    // ── Monthly summary ───────────────────────────────────────────────────────

    /**
     * Returns 12 months of invoice and payment data for the given year.
     * Useful for the annual revenue chart.
     */
    @Transactional(readOnly = true)
    public List<MonthlyReportRow> getMonthlySummary(int year) {
        log.info("Monthly summary for year={}", year);

        List<MonthlyReportRow> rows = new ArrayList<>();

        for (Month month : Month.values()) {
            YearMonth ym      = YearMonth.of(year, month);
            Instant from      = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant to        = ym.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

            List<BusinessInvoice> invoicesInMonth = invoiceRepository
                    .findAllByCreatedAtBetween(from, to);

            BigDecimal invoiceTotal = sum(invoicesInMonth);
            BigDecimal collected    = paymentRepository.sumPaymentsInPeriod(from, to);
            if (collected == null) collected = BigDecimal.ZERO;
            BigDecimal outstanding  = invoiceTotal.subtract(collected).max(BigDecimal.ZERO);

            rows.add(MonthlyReportRow.builder()
                    .year(year)
                    .month(month.getValue())
                    .monthName(month.getDisplayName(
                            java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
                    .invoiceCount(invoicesInMonth.size())
                    .invoiceTotal(invoiceTotal)
                    .collected(collected)
                    .outstanding(outstanding)
                    .build());
        }

        return rows;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private BigDecimal sum(List<BusinessInvoice> invoices) {
        return invoices.stream()
                .map(BusinessInvoice::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}