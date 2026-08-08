package com.mtbs.business.invoice.controller;

import com.mtbs.business.invoice.dto.AddLineItemRequest;
import com.mtbs.business.invoice.dto.BusinessInvoiceResponse;
import com.mtbs.business.invoice.dto.CreateBusinessInvoiceRequest;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.dto.common.PageResponse;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.business.invoice.service.BusinessInvoiceService;
import com.mtbs.shared.annotation.TrackUsage;
import com.mtbs.shared.enums.billing.UsageMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/business-invoices")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
@Tag(name = "Business Invoices",
     description = "Create and manage invoices for your customers. " +
                   "Lifecycle: DRAFT → OPEN (finalize) → PAID (payment recorded) | VOID (cancelled)")
@SecurityRequirement(name = "bearerAuth")
public class BusinessInvoiceController {

    private final BusinessInvoiceService invoiceService;

    // ── POST /api/business-invoices ───────────────────────────────────────────

    @PostMapping
    @TrackUsage(metric = UsageMetric.API_CALLS)
    @Operation(
        summary = "Create an invoice",
        description = "Creates a DRAFT invoice for a customer with one or more line items. " +
                      "Line items can reference catalog products (price/tax snapshotted from product) " +
                      "or be free-text (custom description + price). " +
                      "Additional items can be added while status is DRAFT via POST /items. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessInvoiceResponse>> create(
            @Valid @RequestBody CreateBusinessInvoiceRequest request) {

        BusinessInvoiceResponse response = invoiceService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Invoice created successfully"));
    }

    // ── GET /api/business-invoices ────────────────────────────────────────────

    @GetMapping
    @Operation(
        summary = "List invoices",
        description = "Returns a paginated list of business invoices. " +
                      "Optional filters: customerId and status (DRAFT, OPEN, PAID, VOID). " +
                      "Ordered by creation date descending. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PageResponse<BusinessInvoiceResponse>>> list(
            @Parameter(description = "Filter by customer ID")
            @RequestParam(required = false) Long customerId,

            @Parameter(description = "Filter by invoice status: DRAFT, OPEN, PAID, VOID")
            @RequestParam(required = false) InvoiceStatus status,

            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<BusinessInvoiceResponse> response = invoiceService.list(customerId, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "Invoices fetched successfully"));
    }

    // ── GET /api/business-invoices/{id} ───────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(
        summary = "Get invoice by ID",
        description = "Returns a single invoice with all line items and customer details. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessInvoiceResponse>> getById(@PathVariable Long id) {
        BusinessInvoiceResponse response = invoiceService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Invoice fetched successfully"));
    }

    // ── POST /api/business-invoices/{id}/items ────────────────────────────────

    @PostMapping("/{id}/items")
    @Operation(
        summary = "Add a line item",
        description = "Adds a single line item to a DRAFT invoice. " +
                      "Returns 400 if the invoice is not in DRAFT status. " +
                      "Totals are recalculated and returned in the response. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessInvoiceResponse>> addItem(
            @PathVariable Long id,
            @Valid @RequestBody AddLineItemRequest request) {

        BusinessInvoiceResponse response = invoiceService.addLineItem(id, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Line item added successfully"));
    }

    // ── DELETE /api/business-invoices/{id}/items/{itemId} ─────────────────────

    @DeleteMapping("/{id}/items/{itemId}")
    @Operation(
        summary = "Remove a line item",
        description = "Removes a line item from a DRAFT invoice. " +
                      "Returns 400 if the invoice is not in DRAFT status. " +
                      "Returns 400 if the item does not belong to this invoice. " +
                      "Totals are recalculated and returned in the response. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessInvoiceResponse>> removeItem(
            @PathVariable Long id,
            @PathVariable Long itemId) {

        BusinessInvoiceResponse response = invoiceService.removeLineItem(id, itemId);
        return ResponseEntity.ok(ApiResponse.success(response, "Line item removed successfully"));
    }

    // ── POST /api/business-invoices/{id}/finalize ─────────────────────────────

    @PostMapping("/{id}/finalize")
    @Operation(
        summary = "Finalize an invoice",
        description = "Transitions a DRAFT invoice to OPEN status. " +
                      "Sets the due date to today + 30 days. " +
                      "After finalization, line items can no longer be added or removed. " +
                      "Returns 400 if the invoice has no line items or is not in DRAFT status. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessInvoiceResponse>> finalize(@PathVariable Long id) {
        BusinessInvoiceResponse response = invoiceService.finalize(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Invoice finalized successfully"));
    }

    // ── POST /api/business-invoices/{id}/send ─────────────────────────────────

    @PostMapping("/{id}/send")
    @Operation(
        summary = "Send invoice to customer",
        description = "Fires a BUSINESS_INVOICE_SENT notification event. " +
                      "The NotificationService picks this up asynchronously and emails the customer. " +
                      "Invoice must be in OPEN status before sending. " +
                      "Returns 400 if the invoice is not OPEN. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessInvoiceResponse>> send(@PathVariable Long id) {
        BusinessInvoiceResponse response = invoiceService.send(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Invoice sent to customer successfully"));
    }

    // ── POST /api/business-invoices/{id}/void ─────────────────────────────────

    @PostMapping("/{id}/void")
    @Operation(
        summary = "Void an invoice",
        description = "Marks a DRAFT or OPEN invoice as VOID. " +
                      "Cannot void a PAID invoice — record a payment refund instead. " +
                      "Cannot void an already-voided invoice. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BusinessInvoiceResponse>> voidInvoice(@PathVariable Long id) {
        BusinessInvoiceResponse response = invoiceService.voidInvoice(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Invoice voided successfully"));
    }

    // ── GET /api/business-invoices/{id}/download ──────────────────────────────

    @GetMapping("/{id}/download")
    @Operation(
            summary = "Download invoice as PDF",
            description = "Generates and streams the invoice as a PDF file. " +
                    "Response content-type is application/pdf. " +
                    "Content-Disposition is set to attachment for browser download. " +
                    "Filename: {invoiceNumber}.pdf (e.g. BINV-1-202603-0001.pdf). " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        BusinessInvoiceResponse invoice = invoiceService.getById(id);
        byte[] pdf = invoiceService.generatePdf(id);

        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoice.getInvoiceNumber() + ".pdf\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_LENGTH,
                        String.valueOf(pdf.length))
                .body(pdf);
    }

    // ── POST /api/business-invoices/{id}/payment-link ─────────────────────────

    @PostMapping("/{id}/payment-link")
    @Operation(
            summary = "Generate Razorpay payment link",
            description = "Creates a Razorpay Payment Link for the invoice. " +
                    "The link is a hosted Razorpay checkout page the customer can pay on. " +
                    "Idempotent — returns the existing link ID if one was already created. " +
                    "Invoice must be in OPEN status. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<String>> createPaymentLink(@PathVariable Long id) {
        String paymentLinkId = invoiceService.createPaymentLink(id);
        return ResponseEntity.ok(ApiResponse.success(paymentLinkId,
                "Payment link created successfully"));
    }
}