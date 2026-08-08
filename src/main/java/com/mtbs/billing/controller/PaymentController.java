package com.mtbs.billing.controller;

import com.mtbs.billing.dto.*;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.billing.service.PaymentService;
import com.mtbs.shared.dto.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/${api.version}/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Razorpay payment initiation, verification, refunds, and retry")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    // ── GET /api/payments ─────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "List all payments",
            description = "Returns a paginated list of all payments for the current tenant, " +
                    "ordered by creation date descending. Includes all status payments " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PageResponse<PaymentResponse>>> listPayments(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<PaymentResponse> response = paymentService.listPayments(pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "Payments fetched successfully"));
    }

    // ── POST /api/payments/process/{invoiceId} ────────────────────────────────

    @PostMapping("/process/{invoiceId}")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Initiate payment for an invoice",
            description = "Creates a Razorpay order for the given invoice and stores a payment record " +
                    "with an idempotency key (format: pay-{tenantId}-{invoiceId}). " +
                    "Returns the Razorpay order details needed by the frontend to open the checkout. " +
                    "Safe to call multiple times — idempotency prevents duplicate orders. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<OrderResponse>> processPayment(
            @PathVariable Long invoiceId) {

        OrderResponse response = paymentService.processPayment(invoiceId);
        return ResponseEntity.ok(ApiResponse.success(response, "Payment order created successfully"));
    }

    // ── POST /api/payments/verify ─────────────────────────────────────────────

    @PostMapping("/verify")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Verify and capture payment",
            description = "Verifies the Razorpay payment signature after the user completes checkout. " +
                    "On success: marks payment SUCCEEDED, marks invoice PAID, " +
                    "activates subscription if in trial. " +
                    "Returns 400 if the signature is invalid. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PaymentResponse>> verifyAndCapturePayment(
            @Valid @RequestBody VerifyPaymentRequest request) {

        PaymentResponse response = paymentService.verifyAndCapturePayment(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Payment verified and captured successfully"));
    }

    // ── POST /api/payments/{id}/retry ─────────────────────────────────────────

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Retry a failed payment",
            description = "Creates a new Razorpay order for a previously failed payment. " +
                    "Maximum 3 retry attempts. On the 3rd failure the subscription is suspended. " +
                    "Returns 400 if the payment has exceeded the retry limit. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<OrderResponse>> retryFailedPayment(
            @PathVariable Long id) {

        OrderResponse response = paymentService.retryFailedPayment(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Retry order created successfully"));
    }

    // ── POST /api/payments/{id}/refund ────────────────────────────────────────

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Refund a payment",
            description = "Initiates a full or partial refund via Razorpay for a SUCCEEDED payment. " +
                    "amount=0 or omitted → full refund. " +
                    "amount>0 → partial refund (must not exceed original payment amount). " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PaymentResponse>> refundPayment(
            @PathVariable Long id,
            @Valid @RequestBody RefundRequest request) {

        PaymentResponse response = paymentService.refundPayment(id, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success(response, "Refund initiated successfully"));
    }

    // ── GET /api/payments/{id} ────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Get payment by ID",
            description = "Returns a single payment record by its ID. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PaymentResponse>> getPaymentById(
            @PathVariable Long id) {

        PaymentResponse response = paymentService.getPaymentById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Payment fetched successfully"));
    }

    // ── GET /api/payments/invoice/{invoiceId} ─────────────────────────────────

    @GetMapping("/invoice/{invoiceId}")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Get all payments for an invoice",
            description = "Returns all payment attempts (including failed and retried) for a specific invoice. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getPaymentsByInvoice(
            @PathVariable Long invoiceId) {

        List<PaymentResponse> response = paymentService.getPaymentsByInvoice(invoiceId);
        return ResponseEntity.ok(ApiResponse.success(response, "Payments fetched successfully"));
    }
}