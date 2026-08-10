package com.mtbs.business.invoice.service;

import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.business.product.service.ProductService;
import com.mtbs.business.invoice.dto.AddBillItemRequest;
import com.mtbs.business.invoice.dto.BillItemResponse;
import com.mtbs.business.invoice.dto.BillResponse;
import com.mtbs.business.invoice.dto.CreateBillRequest;
import com.mtbs.business.invoice.dto.CreateBillRequest.InvoiceLineItemRequest;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.business.invoice.mapper.BillMapper;
import com.mtbs.business.invoice.mapper.BillItemMapper;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.product.entity.Product;
import com.mtbs.shared.enums.bill.InvoiceStatus;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.bill.BillEvent;
import com.mtbs.shared.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.business.invoice.repository.BillItemRepository;
import com.mtbs.business.invoice.repository.BillRepository;
import com.mtbs.tenant.service.ShopService;
import com.mtbs.tenant.numbering.enums.NumberSeriesType;
import com.mtbs.tenant.numbering.service.NumberSeriesService;
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
 *   PAID   → transitioned by PaymentService when fully paid
 *   VOID   → voidInvoice() — only from DRAFT or OPEN, never from PAID
 *
 * PDF generation:
 *   BillPdfService is a separate service built in Phase 4.
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
public class BillService {

    private static final int DEFAULT_PAYMENT_TERMS_DAYS = 30;

    private final BillRepository invoiceRepository;
    private final BillItemRepository itemRepository;
    private final CustomerService customerService;
    private final ProductService productService;
    private final ShopService tenantService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final BillMapper invoiceMapper;
    private final BillItemMapper itemMapper;
    private final NumberSeriesService numberSeriesService;

    private final BillPdfService pdfService;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a DRAFT invoice with all provided line items.
     * Validates: customer exists, each product exists and is active (if catalog item).
     * Calculates and stores all financial totals.
     */
    @Transactional
    public BillResponse create(CreateBillRequest request) {
        // Validate customer exists
        Customer customer = customerService.getEntityById(request.getCustomerId());

        Bill invoice = Bill.builder()
                .invoiceNumber(generateInvoiceNumber())
                .customerId(customer.getId())
                .status(InvoiceStatus.DRAFT)
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .notes(request.getNotes())
                .build();

        Bill saved = invoiceRepository.save(invoice);

        // Add line items
        List<BillItem> items = new ArrayList<>();
        if (request.getItems() != null) {
            for (InvoiceLineItemRequest itemReq : request.getItems()) {
                items.add(buildLineItem(saved, itemReq));
            }
        }

        if (!items.isEmpty()) {
            itemRepository.saveAll(items);
            // Do NOT call saved.setItems(items) — items are already persisted above;
            // assigning them to the managed, cascade-ALL "items" collection and then
            // saving the parent would cascade-insert them a second time.
            recalculateTotalsFromDb(saved, saved.getId());
            invoiceRepository.save(saved);
        }

        log.info("Bill created — id={}, number={}, customerId={}",
                saved.getId(), saved.getInvoiceNumber(), customer.getId());
        return mapToInvoiceResponse(saved, customer);
    }

    // ── Line item management (DRAFT only) ─────────────────────────────────────

    @Transactional
    public BillResponse addLineItem(Long invoiceId, AddBillItemRequest request) {
        Bill invoice = findOrThrow(invoiceId);
        assertDraft(invoice);

        BillItem item = buildLineItemFromRequest(invoice, request);
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
    public BillResponse removeLineItem(Long invoiceId, Long itemId) {
        Bill invoice = findOrThrow(invoiceId);
        assertDraft(invoice);

        BillItem item = itemRepository.findById(itemId)
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
    public BillResponse finalize(Long invoiceId) {
        Bill invoice = findOrThrow(invoiceId);
        assertDraft(invoice);

        // Query items separately for validation — do NOT call invoice.setItems()
        long itemCount = itemRepository.countByInvoiceId(invoiceId);
        if (itemCount == 0) {
            throw ResourceException.invalid("Cannot finalize an invoice with no line items");
        }

        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setDueDate(Instant.now().plusSeconds(
                (long) DEFAULT_PAYMENT_TERMS_DAYS * 24 * 60 * 60));

        Bill saved = invoiceRepository.save(invoice);
        log.info("Invoice finalized — id={}, dueDate={}", invoiceId, saved.getDueDate());

        Customer customer = customerService.getEntityById(saved.getCustomerId());
        return mapToInvoiceResponse(saved, customer);
    }

    /**
     * Sends the invoice to the customer via email.
     * Fires BILL_SENT notification event.
     * Invoice must be in OPEN status before sending.
     * (PDF generation is wired in Phase 4 — event fires immediately.)
     */
    @Transactional
    public BillResponse send(Long invoiceId) {
        Bill invoice = findOrThrow(invoiceId);

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
    public BillResponse voidInvoice(Long invoiceId) {
        Bill invoice = findOrThrow(invoiceId);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw ResourceException.invalid(
                "Cannot void a PAID invoice. Record a refund in Payments instead.");
        }
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw ResourceException.invalid("Invoice is already voided.");
        }

        invoice.setStatus(InvoiceStatus.VOID);
        Bill saved = invoiceRepository.save(invoice);
        log.info("Invoice voided — id={}", invoiceId);
        return mapToInvoiceResponse(saved, customerService.getEntityById(saved.getCustomerId()));
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BillResponse getById(Long invoiceId) {
        Bill invoice = findOrThrow(invoiceId);
        return mapToInvoiceResponse(invoice, customerService.getEntityById(invoice.getCustomerId()));
    }

    @Transactional(readOnly = true)
    public Page<BillResponse> list(Long customerId, InvoiceStatus status, Pageable pageable) {
        return invoiceRepository.findWithFilters(customerId, status, pageable)
                .map(inv -> mapToInvoiceResponse(inv,
                        customerService.getEntityById(inv.getCustomerId())));
    }

    // ── Internal — called by PaymentService ───────────────────────────

    /**
     * Transitions invoice to PAID. Called by PaymentService when
     * total payments collected >= invoice.totalAmount.
     */
    @Transactional
    public void markPaid(Long invoiceId) {
        Bill invoice = findOrThrow(invoiceId);
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(Instant.now());
        invoiceRepository.save(invoice);
        log.info("Invoice marked PAID — id={}", invoiceId);
    }

    /**
     * Returns the raw entity — used by PaymentService to validate
     * invoice status and amount before recording a payment.
     */
    @Transactional(readOnly = true)
    public Bill getEntityById(Long invoiceId) {
        return findOrThrow(invoiceId);
    }

    // ── Financials calculation ────────────────────────────────────────────────

    /**
     * Recalculates invoice-level totals from its current line items.
     * Called every time items are added or removed.
     * All values rounded to 2 decimal places (standard for INR).
     */
    private void recalculateTotalsFromDb(Bill invoice, Long invoiceId) {
        List<BillItem> items = itemRepository.findAllByInvoiceId(invoiceId);

        BigDecimal subtotal  = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;

        for (BillItem item : items) {
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
    private BillItem buildLineItem(Bill invoice,
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
     * Builds a line item from an AddBillItemRequest (single item add after creation).
     */
    private BillItem buildLineItemFromRequest(Bill invoice,
                                                         AddBillItemRequest req) {
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

    private BillItem buildItemWithCalculations(Bill invoice,
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

        return BillItem.builder()
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
     * Delegates to the shop's configured NumberSeries (prefix, financial-year
     * format, atomically-incremented sequence) — see NumberSeriesService.
     * Previously hardcoded "BINV-{tenantId}-{yearMonth}-{seq}" derived from a
     * live COUNT(*), which both ignored shop-configurable numbering and had
     * a race condition under concurrent bill creation.
     */
    private String generateInvoiceNumber() {
        return numberSeriesService.nextNumber(NumberSeriesType.INVOICE);
    }

    // ── Guard assertions ──────────────────────────────────────────────────────

    private void assertDraft(Bill invoice) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw ResourceException.invalid(
                "This operation is only allowed on DRAFT invoices. Current status: "
                        + invoice.getStatus());
        }
    }

    private Bill findOrThrow(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Bill", id));
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void fireInvoiceSentEvent(Bill invoice, Customer customer,
                                      String tenantName, byte[] pdfBytes) {
        try {
            java.util.Map<String, Object> extra = new java.util.HashMap<>();

            // invoiceTotal has no direct field on BillEvent — must go in extra
            extra.put("invoiceTotal", invoice.getTotalAmount().toPlainString());

            // dueDate formatted for the template
            if (invoice.getDueDate() != null) {
                extra.put("dueDate", invoice.getDueDate()
                        .atZone(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")));
            }

            // PDF bytes — encode to Base64 for serialization
            String pdfBase64 = null;
            if (pdfBytes != null && pdfBytes.length > 0) {
                pdfBase64 = Base64.getEncoder().encodeToString(pdfBytes);
                extra.put("pdfAttachment", pdfBase64);
            }

            outboxEventPublisher.save(BillEvent.builder()
                    .eventType(NotificationEvent.BILL_SENT)
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
                    .build(), "Bill", invoice.getId());

        } catch (Exception e) {
            log.warn("Failed to fire BILL_SENT for invoiceId={}: {}",
                    invoice.getId(), e.getMessage());
        }
    }

    // ── Response mapping ──────────────────────────────────────────────────────

    private BillResponse mapToInvoiceResponse(Bill invoice, Customer customer) {
        BillResponse response = invoiceMapper.toResponseWithCustomer(
                invoice, customer.getName(), customer.getEmail());

        List<BillItem> items = invoice.getItems() != null && !invoice.getItems().isEmpty()
                ? invoice.getItems()
                : itemRepository.findAllByInvoiceId(invoice.getId());

        List<BillItemResponse> itemResponses = items.stream()
                .map(itemMapper::toResponse)
                .collect(Collectors.toList());

        response.setItems(itemResponses);
        return response;
    }

    public BillResponse mapToResponse(Bill invoice, Customer customer) {
        return mapToInvoiceResponse(invoice, customer);
    }


    /**
     * Generates a PDF byte array for a business invoice.
     * Delegates to BillPdfService which handles all iText layout.
     * Called by GET /api/business-invoices/{id}/download.
     */
    @Transactional(readOnly = true)
    public byte[] generatePdf(Long invoiceId) {
        // Validate invoice exists before handing off to PDF service
        findOrThrow(invoiceId);
        return pdfService.generatePdf(invoiceId);
    }
}