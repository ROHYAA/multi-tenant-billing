package com.mtbs.business.report.controller;

import com.mtbs.business.report.dto.MonthlyReportRow;
import com.mtbs.business.report.dto.OutstandingReportResponse;
import com.mtbs.business.report.dto.RevenueReportResponse;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.business.report.service.BusinessReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.Year;
import java.util.List;

@RestController
@RequestMapping("/api/${api.version}/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
@Tag(name = "Business Reports", description = "Revenue, outstanding, and monthly summary reports")
@SecurityRequirement(name = "bearerAuth")
public class BusinessReportController {

    private final BusinessReportService reportService;

    // ── GET /api/reports/revenue ──────────────────────────────────────────────

    @GetMapping("/revenue")
    @Operation(
        summary = "Revenue report",
        description = "Returns total revenue collected in a date range, broken down by payment method. " +
                      "Revenue is measured from actual payment dates (paid_at), not invoice dates. " +
                      "Both 'from' and 'to' are required ISO-8601 instants. " +
                      "Example: from=2026-01-01T00:00:00Z&to=2026-03-31T23:59:59Z. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<RevenueReportResponse>> getRevenueReport(
            @Parameter(description = "Period start (ISO-8601, e.g. 2026-01-01T00:00:00Z)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Period end (ISO-8601, e.g. 2026-03-31T23:59:59Z)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        if (from.isAfter(to)) {
            throw ResourceException.invalid("'from' must be before 'to'");
        }

        RevenueReportResponse response = reportService.getRevenueReport(from, to);
        return ResponseEntity.ok(ApiResponse.success(response, "Revenue report generated successfully"));
    }

    // ── GET /api/reports/outstanding ──────────────────────────────────────────

    @GetMapping("/outstanding")
    @Operation(
        summary = "Outstanding invoices report",
        description = "Returns all OPEN invoices split into overdue (past due date) " +
                      "and current (not yet due). " +
                      "Shows outstanding balance per invoice (total - payments collected). " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<OutstandingReportResponse>> getOutstandingReport() {
        OutstandingReportResponse response = reportService.getOutstandingReport();
        return ResponseEntity.ok(ApiResponse.success(response, "Outstanding report generated successfully"));
    }

    // ── GET /api/reports/monthly ──────────────────────────────────────────────

    @GetMapping("/monthly")
    @Operation(
        summary = "Monthly summary report",
        description = "Returns 12 months of invoice and payment data for a given year. " +
                      "Each row contains: invoice count, invoice total, amount collected, " +
                      "and outstanding balance for that month. " +
                      "Defaults to the current year if 'year' is not provided. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<List<MonthlyReportRow>>> getMonthlySummary(
            @Parameter(description = "Year (e.g. 2026). Defaults to current year.")
            @RequestParam(required = false) Integer year) {

        int targetYear = (year != null) ? year : Year.now().getValue();

        if (targetYear < 2020 || targetYear > 2100) {
            throw ResourceException.invalid("Year must be between 2020 and 2100");
        }

        List<MonthlyReportRow> response = reportService.getMonthlySummary(targetYear);
        return ResponseEntity.ok(ApiResponse.success(response, "Monthly report generated for year " + targetYear));
    }
}