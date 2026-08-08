package com.mtbs.billing.service;

import com.mtbs.billing.dto.InvoiceLineItemResponse;
import com.mtbs.billing.dto.InvoiceResponse;
import com.mtbs.billing.entity.Invoice;
import com.mtbs.billing.entity.InvoiceLineItem;
import com.mtbs.billing.mapper.InvoiceMapper;
import com.mtbs.billing.mapper.InvoiceLineItemMapper;
import com.mtbs.billing.repository.SubscriptionRepository;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.shared.enums.billing.LineItemType;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.billing.repository.InvoiceLineItemRepository;
import com.mtbs.billing.repository.InvoiceRepository;
import com.mtbs.tenant.service.PlanService;
import com.mtbs.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages the invoice lifecycle.
 *
 * Public API surface (exposed via InvoiceController):
 *  - getInvoiceById(Long)        → GET  /api/invoices/{id}
 *  - listInvoices(Pageable)      → GET  /api/invoices
 *  - voidInvoice(Long, String)   → POST /api/invoices/{id}/void
 *  - generatePdf handled by InvoicePdfService → GET /api/invoices/{id}/download
 *
 * Internal-only (called by BillingCycleJob or PaymentService — no controller):
 *  - generateInvoice(Long, Instant, Instant)
 *  - finalizeInvoice(Long)
 *  - markInvoicePaid(Long, String, Instant)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository lineItemRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanService planService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final TenantService tenantService;
    private final InvoiceMapper invoiceMapper;
    private final InvoiceLineItemMapper lineItemMapper;

    // ── Internal — called by BillingCycleJob ──────────────────────────────────

    /**
     * Generates a DRAFT invoice for the current subscription billing period.
     * Adds a single subscription line item priced at the plan's monthly/annual rate.
     * Called only by BillingCycleJob — never from a controller.
     */
    @Transactional
    public InvoiceResponse generateInvoice(Long subscriptionId, Instant periodStart, Instant periodEnd) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> ResourceException.notFound("Subscription", subscriptionId));

        if (subscription == null) {
            throw ResourceException.notFound("Subscription", subscriptionId);
        }

        Plan plan = planService.getPlanById(subscription.getPlanId());

        BigDecimal price = subscription.getBillingCycle().name().equals("ANNUAL")
                ? planService.getPriceAnnual(plan.getId())
                : planService.getPriceMonthly(plan.getId());

        Invoice invoice = Invoice.builder()
                .subscriptionId(subscriptionId)
                .invoiceNumber(generateInvoiceNumber())
                .status(InvoiceStatus.DRAFT)
                .subtotal(price)
                .taxAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(price)
                .currency(planService.getCurrencyForPlan(plan.getId()))
                .billingPeriodStart(periodStart)
                .billingPeriodEnd(periodEnd)
                .build();

        Invoice saved = invoiceRepository.save(invoice);

        // Add subscription line item
        InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .invoice(saved)
                .description(plan.getDisplayName() + " — "
                        + subscription.getBillingCycle().name().toLowerCase() + " subscription")
                .quantity(BigDecimal.ONE)
                .unitPrice(price)
                .totalPrice(price)
                .lineItemType(LineItemType.SUBSCRIPTION)
                .build();

        lineItemRepository.save(lineItem);

        log.info("Invoice generated — invoiceId={}, number={}, subscriptionId={}",
                saved.getId(), saved.getInvoiceNumber(), subscriptionId);

        fireInvoiceEvent(NotificationEvent.INVOICE_GENERATED, saved, plan);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.INVOICE_GENERATED)
                .entityType(AuditEntityType.INVOICE)
                .entityId(saved.getId())
                .entityName(saved.getInvoiceNumber())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("invoiceNumber", saved.getInvoiceNumber(), "amount", saved.getTotalAmount().toString(), "currency", saved.getCurrency()))
                .description("Invoice generated: " + saved.getInvoiceNumber())
                .module("BILLING")
                .build(), "Invoice", saved.getId());

        return mapToInvoiceResponse(saved);
    }

    /**
     * Moves a DRAFT invoice to OPEN status and sets a 7-day payment due date.
     * Called only by BillingCycleJob — never from a controller.
     */
    @Transactional
    public InvoiceResponse finalizeInvoice(Long invoiceId) {
        Invoice invoice = getInvoiceEntity(invoiceId);

        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw ResourceException.invalid("Only DRAFT invoices can be finalized");
        }

        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setDueDate(Instant.now().plusSeconds(7 * 24 * 60 * 60)); // +7 days
        Invoice saved = invoiceRepository.save(invoice);

        log.info("Invoice finalized — invoiceId={}, dueDate={}", saved.getId(), saved.getDueDate());

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.INVOICE_SENT)
                .entityType(AuditEntityType.INVOICE)
                .entityId(saved.getId())
                .entityName(saved.getInvoiceNumber())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", "DRAFT"))
                .changesAfter(Map.of("status", "OPEN"))
                .description("Invoice finalized and sent: " + saved.getInvoiceNumber())
                .module("BILLING")
                .build(), "Invoice", saved.getId());

        return mapToInvoiceResponse(saved);
    }

    /**
     * Marks an invoice PAID. Called by PaymentService after successful capture.
     * Never called from a controller.
     */
    @Transactional
    public void markInvoicePaid(Long invoiceId, String razorpayPaymentId, Instant paidAt) {
        Invoice invoice = getInvoiceEntity(invoiceId);
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setRazorpayInvoiceId(razorpayPaymentId);
        invoice.setPaidAt(paidAt);
        invoiceRepository.save(invoice);

        Plan plan = resolveInvoicePlan(invoice);
        fireInvoiceEvent(NotificationEvent.INVOICE_PAID, invoice, plan);

        log.info("Invoice marked PAID — invoiceId={}, razorpayPaymentId={}", invoiceId, razorpayPaymentId);
    }

    // ── Public API — exposed via InvoiceController ────────────────────────────

    public InvoiceResponse getInvoiceById(Long invoiceId) {
        return mapToInvoiceResponse(getInvoiceEntity(invoiceId));
    }

    public Page<InvoiceResponse> listInvoices(Pageable pageable) {
        return invoiceRepository.findAll(pageable).map(this::mapToInvoiceResponse);
    }

    /**
     * Voids an OPEN or DRAFT invoice. Cannot void a PAID invoice.
     * Exposed via POST /api/invoices/{id}/void.
     */
    @Transactional
    public InvoiceResponse voidInvoice(Long invoiceId) {
        Invoice invoice = getInvoiceEntity(invoiceId);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw ResourceException.invalid("PAID invoices cannot be voided. Use refund instead.");
        }
        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw ResourceException.invalid("Invoice is already voided");
        }

        invoice.setStatus(InvoiceStatus.VOID);
        Invoice saved = invoiceRepository.save(invoice);

        log.info("Invoice voided — invoiceId={}", invoiceId);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.INVOICE_VOIDED)
                .entityType(AuditEntityType.INVOICE)
                .entityId(saved.getId())
                .entityName(saved.getInvoiceNumber())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", invoice.getStatus().name()))
                .changesAfter(Map.of("status", "VOID"))
                .description("Invoice voided: " + saved.getInvoiceNumber())
                .module("BILLING")
                .severity("WARN")
                .build(), "Invoice", saved.getId());

        return mapToInvoiceResponse(saved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generates a sequence-based invoice number.
     * Format: INV-{tenantId}-{YYYYMM}-{seq}
     * seq = count of invoices in the current month + 1, zero-padded to 4 digits.
     */
    public String generateInvoiceNumber() {
        String yearMonth = YearMonth.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
        Long tenantId = TenantContext.getTenantId();

        long count = invoiceRepository.countByStatus(InvoiceStatus.DRAFT)
                + invoiceRepository.countByStatus(InvoiceStatus.OPEN)
                + invoiceRepository.countByStatus(InvoiceStatus.PAID)
                + 1;

        return String.format("INV-%d-%s-%04d", tenantId, yearMonth, count);
    }

    private Invoice getInvoiceEntity(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("Invoice", invoiceId));
    }

    private Plan resolveInvoicePlan(Invoice invoice) {
        try {
            Subscription sub = subscriptionRepository.findById(invoice.getSubscriptionId())
                    .orElseThrow(() -> ResourceException.notFound("Subscription", invoice.getSubscriptionId()));

            if (sub != null) {
                return planService.getPlanById(sub.getPlanId());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void fireInvoiceEvent(NotificationEvent eventType, Invoice invoice, Plan plan) {
        try {
            BillingEvent.BillingEventBuilder builder = BillingEvent.builder()
                    .eventType(eventType)
                    .invoiceNumber(invoice.getInvoiceNumber())
                    .invoiceAmount(invoice.getTotalAmount())
                    .currency(invoice.getCurrency())
                    .invoiceDueDate(invoice.getDueDate());

            if (plan != null) {
                builder.planName(plan.getName());
            }

            outboxEventPublisher.save(builder.build(), "Invoice", invoice.getId());
        } catch (Exception e) {
            log.warn("Failed to fire {} event for invoiceId={}: {}", eventType, invoice.getId(), e.getMessage());
        }
    }

    // ── Response mapping ──────────────────────────────────────────────────────

    private InvoiceResponse mapToInvoiceResponse(Invoice invoice) {
        InvoiceResponse response = invoiceMapper.toResponse(invoice);
        
        List<InvoiceLineItemResponse> items = lineItemRepository
                .findByInvoiceId(invoice.getId())
                .stream()
                .map(lineItemMapper::toResponse)
                .collect(Collectors.toList());

        response.setLineItems(items);
        return response;
    }
}