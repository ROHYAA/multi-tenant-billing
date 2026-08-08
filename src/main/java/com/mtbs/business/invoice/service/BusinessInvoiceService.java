package com.mtbs.business.invoice.service;

import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.business.product.service.ProductService;
import com.mtbs.business.invoice.dto.AddLineItemRequest;
import com.mtbs.business.invoice.dto.BusinessInvoiceItemResponse;
import com.mtbs.business.invoice.dto.BusinessInvoiceResponse;
import com.mtbs.business.invoice.dto.CreateBusinessInvoiceRequest;
import com.mtbs.business.invoice.dto.CreateBusinessInvoiceRequest.InvoiceLineItemRequest;
import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.business.invoice.entity.BusinessInvoiceItem;
import com.mtbs.business.invoice.mapper.BusinessInvoiceMapper;
import com.mtbs.business.invoice.mapper.BusinessInvoiceItemMapper;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.product.entity.Product;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.business.invoice.repository.BusinessInvoiceItemRepository;
import com.mtbs.business.invoice.repository.BusinessInvoiceRepository;
import com.mtbs.billing.gateway.PaymentGatewayPort;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.shared.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core service for the business billing domain.
 *
 * Invoice lifecycle enforced here:
 *   DRAFT  → items can be added/removed, totals recalculated
 *   OPEN   → finalizeInvoice() — sets due date, locks from edits
 *   PAID   → transitioned by BusinessPaymentService when fully paid
 *   VOID   → voidInvoice() — only from DRAFT or OPEN, never from PAID
 *
 * PDF generation:
 *   BusinessInvoicePdfService is a separate service built in Phase 4.
 *   sendInvoice() calls it and emails the customer.
 *   For now the download endpoint delegates to InvoicePdfService — we add
 *   the business-specific overload in Phase 4.
 *
 * Snapshot principle:
 *   When a line item is added from the product catalog, unit_price and
 *   tax_percentage are copied from the product at that instant. Future
 *   product price changes do NOT affect this invoice.
 *
 * Calculation (stored, never recomputed on read):
 *   itemTax   = ROUND((unitPrice × qty) × (taxPct / 100), 2)
 *   itemTotal = ROUND((unitPrice × qty) + itemTax, 2)
 *   invoice.subtotal   = sum(unitPrice × qty)
 *   invoice.taxAmount  = sum(itemTax)
 *   invoice.totalAmount = subtotal + taxAmount
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessInvoiceService {

    private static final int DEFAULT_PAYMENT_TERMS_DAYS = 30;

    private final BusinessInvoiceRepository invoiceRepository;
    private final BusinessInvoiceItemRepository itemRepository;
    private final CustomerService customerService;
    private final ProductService productService;
    private final TenantService tenantService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final BusinessInvoiceMapper invoiceMapper;
    private final BusinessInvoiceItemMapper itemMapper;

    private final BusinessInvoicePdfService pdfService;
    private final PaymentGatewayPort paymentGateway;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a DRAFT invoice with all provided line items.
     * Validates: customer exists, each product exists and is active (if catalog item).
     * Calculates and stores all financial totals.
     */
    @Transactional
    public BusinessInvoiceResponse create(CreateBusinessInvoiceRequest request) {
        // Validate customer exists
        Customer customer = customerService.getEntityById(request.getCustomerId());

        BusinessInvoice invoice = BusinessInvoice.builder()
                .invoiceNumber(generateInvoiceNumber())
                .customerId(customer.getId())
                .status(InvoiceStatus.DRAFT)
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .notes(request.getNotes())
                .build();

        BusinessInvoice saved = invoiceRepository.save(invoice);

        // Add line items
        List<BusinessInvoiceItem> items = new ArrayList<>();
        if (request.getItems() != null) {
            for (InvoiceLineItemRequest itemReq : request.getItems()) {
                items.add(buildLineItem(saved, itemReq));
            }
        }

        if (!items.isEmpty()) {
            itemRepository.saveAll(items);
            saved.setItems(items);
            recalculateTotals(saved);
            invoiceRepository.save(saved);
        }

        log.info("BusinessInvoice created — id={}, number={}, customerId={}",
                saved.getId(), saved.getInvoiceNumber(), customer.getId());
        return mapToInvoiceResponse(saved, customer);
    }

    // ── Line item management (DRAFT only) ─────────────────────────────────────

    @Transactional
    public BusinessInvoiceResponse addLineItem(Long invoiceId, AddLineItemRequest request) {
        BusinessInvoice invoice = findOrThrow(invoiceId);
        assertDraft(invoice);

        BusinessInvoiceItem item = buildLineItemFromRequest(invoice, request);
        itemRepository.save(item);
        itemRepository.flush();

        // Recalculate totals by querying — do NOT call invoice.setItems()
        recalculateTotalsFromDb(invoice, invoiceId);
        invoiceRepository.save(invoice);

        log.info("Line item added to invoice {} — description={}", invoiceId, request.getDescription());
        Customer customer = customerService.getEntityById(invoice.getCustomerId());
        return mapToInvoiceResponse(invoice, customer);
    }

    @Transactional
    public BusinessInvoiceResponse removeLineItem(Long invoiceId, Long itemId) {
        BusinessInvoice invoice = findOrThrow(invoiceId);
        assertDraft(invoice);

        BusinessInvoiceItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> ResourceException.notFound("InvoiceItem", itemId));

        if (!item.getInvoice().getId().equals(invoiceId)) {
            throw ResourceException.accessDenied("Line item does not belong to this invoice");
        }

        itemRepository.delete(item);
        itemRepository.flush();

        // Recalculate totals by querying — do NOT call invoice.setItems()
        recalculateTotalsFromDb(invoice, invoiceId);
        invoiceRepository.save(invoice);

        log.info("Line item {} removed from invoice {}", itemId, invoiceId);
        Customer customer = customerService.getEntityById(invoice.getCustomerId());
        return mapToInvoiceResponse(invoice, customer);
    }

    // ── Lifecycle transitions ─────────────────────────────────────────────────

    /**
     * DRAFT → OPEN.
     * Validates the invoice has at least one line item.
     * Sets due date = NOW + DEFAULT_PAYMENT_TERMS_DAYS.
     * After this point the invoice is locked — no item edits allowed.
     */
    @Transactional
    public BusinessInvoiceResponse finalize(Long invoiceId) {
        BusinessInvoice invoice = findOrThrow(invoiceId);
        assertDraft(invoice);

        // Query items separately for validation — do NOT call invoice.setItems()
        long itemCount = itemRepository.countByInvoiceId(invoiceId);
        if (itemCount == 0) {
            throw ResourceException.invalid("Cannot finalize an invoice with no line items");
        }

        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setDueDate(Instant.now().plusSeconds(
                (long) DEFAULT_PAYMENT_TERMS_DAYS * 24 * 60 * 60));

        BusinessInvoice saved = invoiceRepository.save(invoice);
        log.info("Invoice finalized — id={}, dueDate={}", invoiceId, saved.getDueDate());

        Customer customer = customerService.getEntityById(saved.getCustomerId());
        return mapToInvoiceResponse(saved, customer);
    }

    /**
     * Sends the invoice to the customer via email.
     * Fires BUSINESS_INVOICE_SENT notification event.
     * Invoice must be in OPEN status before sending.
     * (PDF generation is wired in Phase 4 — event fires immediately.)
     */
    @Transactional
    public BusinessInvoiceResponse send(Long invoiceId) {
        BusinessInvoice invoice = findOrThrow(invoiceId);

        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw ResourceException.invalid(
                    "Only OPEN invoices can be sent. Current status: " + invoice.getStatus());
        }

        Customer customer = customerService.getEntityById(invoice.getCustomerId());
        Long tenantId = SecurityUtils.getCurrentTenantId();
        String tenantName = tenantService.getTenantNameById(tenantId);

        // Generate PDF bytes — passed to NotificationService via event extras
        byte[] pdfBytes = null;
        try {
            pdfBytes = pdfService.generatePdf(invoiceId);
        } catch (Exception e) {
            log.warn("PDF generation failed during send — sending without attachment: {}", e.getMessage());
        }

        fireInvoiceSentEvent(invoice, customer, tenantName, pdfBytes);

        log.info("Invoice send event fired — id={}, customerEmail={}", invoiceId, customer.getEmail());
        return mapToInvoiceResponse(invoice, customer);
    }

    /**
     * DRAFT or OPEN → VOID.
     * Cannot void a PAID invoice — use a refund instead.
     */
    @Transactional
    public BusinessInvoiceResponse voidInvoice(Long invoiceId) {
        BusinessInvoice invoice = findOrThrow(invoiceId);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw ResourceException.invalid(
                "Cannot void a PAID invoice. Record a refund in BusinessPayments instead.");
        }
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw ResourceException.invalid("Invoice is already voided.");
        }

        invoice.setStatus(InvoiceStatus.VOID);
        BusinessInvoice saved = invoiceRepository.save(invoice);
        log.info("Invoice voided — id={}", invoiceId);
        return mapToInvoiceResponse(saved, customerService.getEntityById(saved.getCustomerId()));
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BusinessInvoiceResponse getById(Long invoiceId) {
        BusinessInvoice invoice = findOrThrow(invoiceId);
        return mapToInvoiceResponse(invoice, customerService.getEntityById(invoice.getCustomerId()));
    }

    @Transactional(readOnly = true)
    public Page<BusinessInvoiceResponse> list(Long customerId, InvoiceStatus status, Pageable pageable) {
        return invoiceRepository.findWithFilters(customerId, status, pageable)
                .map(inv -> mapToInvoiceResponse(inv,
                        customerService.getEntityById(inv.getCustomerId())));
    }

    // ── Internal — called by BusinessPaymentService ───────────────────────────

    /**
     * Transitions invoice to PAID. Called by BusinessPaymentService when
     * total payments collected >= invoice.totalAmount.
     */
    @Transactional
    public void markPaid(Long invoiceId) {
        BusinessInvoice invoice = findOrThrow(invoiceId);
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(Instant.now());
        invoiceRepository.save(invoice);
        log.info("Invoice marked PAID — id={}", invoiceId);
    }

    /**
     * Returns the raw entity — used by BusinessPaymentService to validate
     * invoice status and amount before recording a payment.
     */
    @Transactional(readOnly = true)
    public BusinessInvoice getEntityById(Long invoiceId) {
        return findOrThrow(invoiceId);
    }

    // ── Financials calculation ────────────────────────────────────────────────

    /**
     * Recalculates invoice-level totals from its current line items.
     * Called every time items are added or removed.
     * All values rounded to 2 decimal places (standard for INR).
     */
    private void recalculateTotals(BusinessInvoice invoice) {
        BigDecimal subtotal  = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;

        for (BusinessInvoiceItem item : invoice.getItems()) {
            BigDecimal lineBase = item.getUnitPrice()
                    .multiply(item.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(lineBase);
            taxAmount = taxAmount.add(item.getTaxAmount());
        }

        invoice.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        invoice.setTaxAmount(taxAmount.setScale(2, RoundingMode.HALF_UP));
        invoice.setTotalAmount(subtotal.add(taxAmount).setScale(2, RoundingMode.HALF_UP));
    }

    private void recalculateTotalsFromDb(BusinessInvoice invoice, Long invoiceId) {
        List<BusinessInvoiceItem> items = itemRepository.findAllByInvoiceId(invoiceId);

        BigDecimal subtotal  = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;

        for (BusinessInvoiceItem item : items) {
            BigDecimal lineBase = item.getUnitPrice()
                    .multiply(item.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP);
            subtotal  = subtotal.add(lineBase);
            taxAmount = taxAmount.add(item.getTaxAmount());
        }

        invoice.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        invoice.setTaxAmount(taxAmount.setScale(2, RoundingMode.HALF_UP));
        invoice.setTotalAmount(subtotal.add(taxAmount).setScale(2, RoundingMode.HALF_UP));
    }

    // ── Line item builders ────────────────────────────────────────────────────

    /**
     * Builds a line item during invoice creation from the nested request DTO.
     * If productId is provided: snapshots price/tax from product.
     * If productId is null: uses values from the request directly (free-text item).
     */
    private BusinessInvoiceItem buildLineItem(BusinessInvoice invoice,
                                              InvoiceLineItemRequest req) {
        BigDecimal unitPrice     = req.getUnitPrice();
        BigDecimal taxPercentage = req.getTaxPercentage() != null
                ? req.getTaxPercentage() : BigDecimal.ZERO;
        String description       = req.getDescription();
        Long productId           = req.getProductId();

        if (productId != null) {
            Product product = productService.getEntityById(productId);
            if (!product.getIsActive()) {
                throw ResourceException.invalid(
                    "Product '" + product.getName() + "' is deactivated and cannot be added to an invoice.");
            }
            // Snapshot from catalog
            unitPrice     = product.getPrice();
            taxPercentage = product.getTaxPercentage();
            description   = product.getName();
        }

        return buildItemWithCalculations(invoice, productId, description,
                req.getQuantity(), unitPrice, taxPercentage);
    }

    /**
     * Builds a line item from an AddLineItemRequest (single item add after creation).
     */
    private BusinessInvoiceItem buildLineItemFromRequest(BusinessInvoice invoice,
                                                         AddLineItemRequest req) {
        BigDecimal unitPrice     = req.getUnitPrice();
        BigDecimal taxPercentage = req.getTaxPercentage() != null
                ? req.getTaxPercentage() : BigDecimal.ZERO;
        String description       = req.getDescription();
        Long productId           = req.getProductId();

        if (productId != null) {
            Product product = productService.getEntityById(productId);
            if (!product.getIsActive()) {
                throw ResourceException.invalid(
                    "Product '" + product.getName() + "' is deactivated.");
            }
            unitPrice     = product.getPrice();
            taxPercentage = product.getTaxPercentage();
            description   = product.getName();
        }

        return buildItemWithCalculations(invoice, productId, description,
                req.getQuantity(), unitPrice, taxPercentage);
    }

    private BusinessInvoiceItem buildItemWithCalculations(BusinessInvoice invoice,
                                                          Long productId,
                                                          String description,
                                                          BigDecimal quantity,
                                                          BigDecimal unitPrice,
                                                          BigDecimal taxPercentage) {
        // tax   = (unitPrice × qty) × (taxPct / 100)
        // total = (unitPrice × qty) + tax
        BigDecimal lineBase = unitPrice.multiply(quantity)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount = lineBase
                .multiply(taxPercentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = lineBase.add(taxAmount)
                .setScale(2, RoundingMode.HALF_UP);

        return BusinessInvoiceItem.builder()
                .invoice(invoice)
                .productId(productId)
                .description(description)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .taxPercentage(taxPercentage)
                .taxAmount(taxAmount)
                .total(total)
                .build();
    }

    // ── Invoice number generation ─────────────────────────────────────────────

    /**
     * Format: BINV-{tenantId}-{YYYYMM}-{seq:04d}
     * "B" prefix distinguishes from platform INV-* numbers.
     * Sequence is derived from total invoice count — monotonically increasing.
     */
    private String generateInvoiceNumber() {
        String yearMonth = YearMonth.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
        Long tenantId = SecurityUtils.getCurrentTenantId();
        long seq      = invoiceRepository.countAllIncludingVoid() + 1;
        return String.format("BINV-%d-%s-%04d", tenantId, yearMonth, seq);
    }

    // ── Guard assertions ──────────────────────────────────────────────────────

    private void assertDraft(BusinessInvoice invoice) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw ResourceException.invalid(
                "This operation is only allowed on DRAFT invoices. Current status: "
                        + invoice.getStatus());
        }
    }

    private BusinessInvoice findOrThrow(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("BusinessInvoice", id));
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void fireInvoiceSentEvent(BusinessInvoice invoice, Customer customer,
                                      String tenantName, byte[] pdfBytes) {
        try {
            java.util.Map<String, Object> extra = new java.util.HashMap<>();

            // invoiceTotal has no direct field on BillingEvent — must go in extra
            extra.put("invoiceTotal", invoice.getTotalAmount().toPlainString());

            // dueDate formatted for the template
            if (invoice.getDueDate() != null) {
                extra.put("dueDate", invoice.getDueDate()
                        .atZone(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")));
            }

            // paymentLinkUrl — only if a Razorpay link was generated
            if (invoice.getRazorpayPaymentLinkId() != null) {
                extra.put("paymentLinkUrl", invoice.getRazorpayPaymentLinkId());
            }

            // PDF bytes — encode to Base64 for serialization
            String pdfBase64 = null;
            if (pdfBytes != null && pdfBytes.length > 0) {
                pdfBase64 = Base64.getEncoder().encodeToString(pdfBytes);
                extra.put("pdfAttachment", pdfBase64);
            }

            outboxEventPublisher.save(BillingEvent.builder()
                    .eventType(NotificationEvent.BUSINESS_INVOICE_SENT)
                    .tenantId(SecurityUtils.getCurrentTenantId())
                    .tenantName(tenantName)
                    .recipientEmail(customer.getEmail())
                    .recipientName(customer.getName())
                    // Set direct fields — these are what buildBillingContext() reads first
                    .invoiceNumber(invoice.getInvoiceNumber())
                    .invoiceAmount(invoice.getTotalAmount())
                    .currency(invoice.getCurrency() != null ? invoice.getCurrency() : "INR")
                    .invoiceDueDate(invoice.getDueDate())
                    .extra(extra)
                    .pdfAttachmentBase64(pdfBase64)
                    .build(), "BusinessInvoice", invoice.getId());

        } catch (Exception e) {
            log.warn("Failed to fire BUSINESS_INVOICE_SENT for invoiceId={}: {}",
                    invoice.getId(), e.getMessage());
        }
    }

    // ── Response mapping ──────────────────────────────────────────────────────

    private BusinessInvoiceResponse mapToInvoiceResponse(BusinessInvoice invoice, Customer customer) {
        BusinessInvoiceResponse response = invoiceMapper.toResponseWithCustomer(
                invoice, customer.getName(), customer.getEmail());

        List<BusinessInvoiceItem> items = invoice.getItems() != null && !invoice.getItems().isEmpty()
                ? invoice.getItems()
                : itemRepository.findAllByInvoiceId(invoice.getId());

        List<BusinessInvoiceItemResponse> itemResponses = items.stream()
                .map(itemMapper::toResponse)
                .collect(Collectors.toList());

        response.setItems(itemResponses);
        return response;
    }

    public BusinessInvoiceResponse mapToResponse(BusinessInvoice invoice, Customer customer) {
        return mapToInvoiceResponse(invoice, customer);
    }


    /**
     * Generates a PDF byte array for a business invoice.
     * Delegates to BusinessInvoicePdfService which handles all iText layout.
     * Called by GET /api/business-invoices/{id}/download.
     */
    @Transactional(readOnly = true)
    public byte[] generatePdf(Long invoiceId) {
        // Validate invoice exists before handing off to PDF service
        findOrThrow(invoiceId);
        return pdfService.generatePdf(invoiceId);
    }

// ─────────────────────────────────────────────────────────────────────────────
// ADD: createPaymentLink() — replaces the stub in BusinessInvoiceController
// ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a Razorpay Payment Link for an OPEN invoice and stores the link ID.
     * Idempotent: if a link already exists (razorpayPaymentLinkId is set), returns
     * the existing ID without creating a new one.
     *
     * Called by POST /api/business-invoices/{id}/payment-link.
     */
    @Transactional
    public String createPaymentLink(Long invoiceId) {
        BusinessInvoice invoice = findOrThrow(invoiceId);

        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw ResourceException.invalid(
                    "Payment links can only be created for OPEN invoices. " +
                            "Current status: " + invoice.getStatus());
        }

        // Idempotent — return existing link if already created
        if (invoice.getRazorpayPaymentLinkId() != null) {
            log.info("Returning existing payment link for invoiceId={}", invoiceId);
            return invoice.getRazorpayPaymentLinkId();
        }

        Customer customer = customerService.getEntityById(invoice.getCustomerId());

        // Convert totalAmount to paise (Razorpay requires smallest currency unit)
        long amountPaise = invoice.getTotalAmount()
                .multiply(java.math.BigDecimal.valueOf(100))
                .longValue();

        String paymentLinkId = paymentGateway.createPaymentLink(
                amountPaise,
                invoice.getCurrency(),
                "Invoice " + invoice.getInvoiceNumber(),
                customer.getEmail(),
                customer.getName(),
                customer.getPhone(),
                invoice.getInvoiceNumber()   // receipt = invoice number as idempotency key
        );

        invoice.setRazorpayPaymentLinkId(paymentLinkId);
        invoiceRepository.save(invoice);

        log.info("Payment link created — invoiceId={}, linkId={}", invoiceId, paymentLinkId);
        return paymentLinkId;
    }
}