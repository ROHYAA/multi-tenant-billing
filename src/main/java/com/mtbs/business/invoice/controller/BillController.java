package com.mtbs.business.invoice.controller;

import com.mtbs.business.invoice.dto.AddBillItemRequest;
import com.mtbs.business.invoice.dto.BillResponse;
import com.mtbs.business.invoice.dto.CreateBillRequest;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.dto.common.PageResponse;
import com.mtbs.shared.enums.bill.InvoiceStatus;
import com.mtbs.business.invoice.service.BillService;
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
@Tag(name = "Bills",
     description = "Create and manage invoices for your customers. " +
                   "Lifecycle: DRAFT → OPEN (finalize) → PAID (payment recorded) | VOID (cancelled)")
@SecurityRequirement(name = "bearerAuth")
public class BillController {

    private final BillService invoiceService;

    // ── POST /api/business-invoices ───────────────────────────────────────────

    @PostMapping
    @Operation(
        summary = "Create an invoice",
        description = "Creates a DRAFT invoice for a customer with one or more line items. " +
                      "Line items can reference catalog products (price/tax snapshotted from product) " +
                      "or be free-text (custom description + price). " +
                      "Additional items can be added while status is DRAFT via POST /items. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BillResponse>> create(
            @Valid @RequestBody CreateBillRequest request) {

        BillResponse response = invoiceService.create(request);
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
    public ResponseEntity<ApiResponse<PageResponse<BillResponse>>> list(
            @Parameter(description = "Filter by customer ID")
            @RequestParam(required = false) Long customerId,

            @Parameter(description = "Filter by invoice status: DRAFT, OPEN, PAID, VOID")
            @RequestParam(required = false) InvoiceStatus status,

            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<BillResponse> response = invoiceService.list(customerId, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "Invoices fetched successfully"));
    }

    // ── GET /api/business-invoices/{id} ───────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(
        summary = "Get invoice by ID",
        description = "Returns a single invoice with all line items and customer details. " +
                      "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<BillResponse>> getById(@PathVariable Long id) {
        BillResponse response = invoiceService.getById(id);
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
    public ResponseEntity<ApiResponse<BillResponse>> addItem(
            @PathVariable Long id,
            @Valid @RequestBody AddBillItemRequest request) {

        BillResponse response = invoiceService.addLineItem(id, request);
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
    public ResponseEntity<ApiResponse<BillResponse>> removeItem(
            @PathVariable Long id,
            @PathVariable Long itemId) {

        BillResponse response = invoiceService.removeLineItem(id, itemId);
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
    public ResponseEntity<ApiResponse<BillResponse>> finalize(@PathVariable Long id) {
        BillResponse response = invoiceService.finalize(id);
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
    public ResponseEntity<ApiResponse<BillResponse>> send(@PathVariable Long id) {
        BillResponse response = invoiceService.send(id);
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
    public ResponseEntity<ApiResponse<BillResponse>> voidInvoice(@PathVariable Long id) {
        BillResponse response = invoiceService.voidInvoice(id);
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
        BillResponse invoice = invoiceService.getById(id);
        byte[] pdf = invoiceService.generatePdf(id);

        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoice.getInvoiceNumber() + ".pdf\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_LENGTH,
                        String.valueOf(pdf.length))
                .body(pdf);
    }
}