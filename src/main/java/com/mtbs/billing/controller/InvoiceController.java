package com.mtbs.billing.controller;

import com.mtbs.billing.dto.InvoiceResponse;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.billing.service.InvoiceService;
import com.mtbs.billing.service.InvoicePdfService;
import com.mtbs.shared.dto.common.PageResponse;
import com.mtbs.shared.annotation.TrackUsage;
import com.mtbs.shared.enums.billing.UsageMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/invoices")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Invoice listing, detail, void, and PDF download")
@SecurityRequirement(name = "bearerAuth")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;

    // ── GET /api/invoices ─────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "List all invoices",
            description = "Returns a paginated list of all invoices for the current tenant, " +
                    "ordered by creation date descending. Includes line items. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<PageResponse<InvoiceResponse>>> listInvoices(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<InvoiceResponse> response = invoiceService.listInvoices(pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(response), "Invoices fetched successfully"));
    }

    // ── GET /api/invoices/{id} ────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Get invoice by ID",
            description = "Returns a single invoice with all line items. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoiceById(
            @PathVariable Long id) {

        InvoiceResponse response = invoiceService.getInvoiceById(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Invoice fetched successfully"));
    }

    // ── POST /api/invoices/{id}/void ──────────────────────────────────────────

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Void an invoice",
            description = "Marks a DRAFT or OPEN invoice as VOID. " +
                    "Cannot void a PAID invoice — use a refund instead. " +
                    "Cannot void an already-voided invoice. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<ApiResponse<InvoiceResponse>> voidInvoice(
            @PathVariable Long id) {

        InvoiceResponse response = invoiceService.voidInvoice(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Invoice voided successfully"));
    }

    // ── GET /api/invoices/{id}/download ───────────────────────────────────────

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('PERMISSION_BILLING_MANAGE')")
    @Operation(
            summary = "Download invoice as PDF",
            description = "Generates and returns the invoice as a PDF file. " +
                    "Response content type is application/pdf. " +
                    "Content-Disposition header is set to attachment for browser download. " +
                    "Requires BILLING_MANAGE permission."
    )
    public ResponseEntity<byte[]> downloadInvoicePdf(@PathVariable Long id) {

        // Verify the invoice exists before generating the PDF
        InvoiceResponse invoice = invoiceService.getInvoiceById(id);

        byte[] pdf = invoicePdfService.generatePdf(id);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoice.getInvoiceNumber() + ".pdf\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.length))
                .body(pdf);
    }
}