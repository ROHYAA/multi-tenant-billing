package com.mtbs.business.payment.controller;

import com.mtbs.business.payment.dto.BusinessPaymentResponse;
import com.mtbs.business.payment.dto.RecordPaymentRequest;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.business.payment.service.BusinessPaymentService;
import com.mtbs.shared.annotation.TrackUsage;
import com.mtbs.shared.enums.billing.UsageMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/${api.version}/business-payments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
@Tag(name = "Business Payments",
     description = "Record and track payments received from customers against business invoices")
@SecurityRequirement(name = "bearerAuth")
public class BusinessPaymentController {

    private final BusinessPaymentService paymentService;

    // ── POST /api/business-payments/{invoiceId} ───────────────────────────────

    @PostMapping("/{invoiceId}")
    @TrackUsage(metric = UsageMetric.API_CALLS)
    @Operation(
        summary = "Record a payment",
        description = "Records a payment received from a customer against an OPEN invoice. " +
                      "Supports partial payments — call multiple times until fully paid. " +
                      "When total payments collected >= invoice total amount, the invoice " +
                      "is automatically transitioned to PAID. " +
                      "Returns 400 if the amount exceeds the outstanding balance. " +
                      "Returns 400 if the invoice is not in OPEN status. " +
                      "paidAt defaults to now if not provided — use this to backdate " +
                      "an offline payment that happened in the past. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessPaymentResponse>> record(
            @PathVariable Long invoiceId,
            @Valid @RequestBody RecordPaymentRequest request) {

        BusinessPaymentResponse response = paymentService.record(invoiceId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Payment recorded successfully"));
    }

    // ── GET /api/business-payments/invoice/{invoiceId} ────────────────────────

    @GetMapping("/invoice/{invoiceId}")
    @Operation(
        summary = "List payments for an invoice",
        description = "Returns all payment records for a specific invoice, including partial payments. " +
                      "Useful for showing a payment history timeline on the invoice detail page. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<List<BusinessPaymentResponse>>> listByInvoice(
            @PathVariable Long invoiceId) {

        List<BusinessPaymentResponse> response = paymentService.listByInvoice(invoiceId);
        return ResponseEntity.ok(ApiResponse.success(response, "Payments fetched successfully"));
    }

    // ── GET /api/business-payments/invoice/{invoiceId}/outstanding ────────────

    @GetMapping("/invoice/{invoiceId}/outstanding")
    @Operation(
        summary = "Get outstanding balance for an invoice",
        description = "Returns the remaining amount owed by the customer for an invoice. " +
                      "outstanding = invoice.totalAmount - sum(recorded payments). " +
                      "Returns 0 for fully paid invoices. " +
                      "Use this to pre-fill the amount field when recording a payment. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BigDecimal>> getOutstanding(@PathVariable Long invoiceId) {
        BigDecimal outstanding = paymentService.getOutstandingBalance(invoiceId);
        return ResponseEntity.ok(ApiResponse.success(outstanding, "Outstanding balance fetched successfully"));
    }
}