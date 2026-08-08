package com.mtbs.business.payment.service;

import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.business.invoice.service.BusinessInvoiceService;
import com.mtbs.business.payment.dto.BusinessPaymentResponse;
import com.mtbs.business.payment.dto.RecordPaymentRequest;
import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.business.payment.entity.BusinessPayment;
import com.mtbs.business.payment.mapper.BusinessPaymentMapper;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.business.payment.repository.BusinessPaymentRepository;
import com.mtbs.billing.gateway.PaymentGatewayPort;
import com.mtbs.tenant.service.TenantService;
import com.mtbs.shared.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Records payments received from customers and manages payment links.
 *
 * Partial payment support:
 *   Multiple payments can exist per invoice. The invoice transitions to PAID
 *   only when sum(payments) >= invoice.totalAmount. This handles scenarios
 *   like a customer paying 50% upfront and 50% on delivery.
 *
 * Payment link:
 *   Razorpay Payment Links are created on demand. The link is stored on the
 *   invoice and returned to the tenant to share with the customer.
 *   Note: PaymentGatewayPort does not currently expose createPaymentLink() —
 *   this is added to the interface as part of Phase 4. The method stub is
 *   wired here so the service compiles — the implementation adds the real call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessPaymentService {

    private final BusinessPaymentRepository paymentRepository;
    private final BusinessInvoiceService invoiceService;
    private final CustomerService customerService;
    private final TenantService tenantService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PaymentGatewayPort paymentGateway;
    private final BusinessPaymentMapper paymentMapper;

    // ── Record payment ────────────────────────────────────────────────────────

    /**
     * Records a payment received from a customer.
     *
     * Rules:
     *  - Invoice must be OPEN (not DRAFT, not already PAID, not VOID)
     *  - Amount must be > 0
     *  - Amount must not exceed the outstanding balance
     *  - If total payments collected >= invoice.totalAmount → mark invoice PAID
     *
     * Supports partial payments — call multiple times until fully paid.
     */
    @Transactional
    public BusinessPaymentResponse record(Long invoiceId, RecordPaymentRequest request) {
        BusinessInvoice invoice = invoiceService.getEntityById(invoiceId);

        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw ResourceException.invalid(
                "Payments can only be recorded against OPEN invoices. " +
                "Current status: " + invoice.getStatus());
        }

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw ResourceException.invalid("Payment amount must be greater than zero.");
        }

        // Validate amount does not exceed outstanding balance
        BigDecimal alreadyPaid   = paymentRepository.sumAmountByInvoiceId(invoiceId);
        BigDecimal outstanding   = invoice.getTotalAmount().subtract(alreadyPaid);

        if (request.getAmount().compareTo(outstanding) > 0) {
            throw ResourceException.invalid(String.format(
                "Payment amount ₹%.2f exceeds outstanding balance ₹%.2f.",
                request.getAmount(), outstanding));
        }

        BusinessPayment payment = BusinessPayment.builder()
                .invoiceId(invoiceId)
                .amount(request.getAmount())
                .currency(invoice.getCurrency())
                .method(request.getMethod())
                .notes(request.getNotes())
                .paidAt(request.getPaidAt() != null ? request.getPaidAt() : java.time.Instant.now())
                .build();

        BusinessPayment saved = paymentRepository.save(payment);
        log.info("Payment recorded — id={}, invoiceId={}, amount={}, method={}",
                saved.getId(), invoiceId, saved.getAmount(), saved.getMethod());

        // Check if invoice is now fully paid
        BigDecimal totalPaid = alreadyPaid.add(request.getAmount());
        if (totalPaid.compareTo(invoice.getTotalAmount()) >= 0) {
            invoiceService.markPaid(invoiceId);
            log.info("Invoice fully paid — invoiceId={}, totalCollected={}", invoiceId, totalPaid);

            // Fire BUSINESS_PAYMENT_RECORDED event after full payment
            Customer customer = customerService.getEntityById(invoice.getCustomerId());
            firePaymentEvent(invoice, customer, saved);
        }

        return paymentMapper.toResponse(saved);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BusinessPaymentResponse> listByInvoice(Long invoiceId) {
        invoiceService.getEntityById(invoiceId);

        return paymentRepository.findAllByInvoiceId(invoiceId)
                .stream()
                .map(paymentMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Returns the outstanding balance for an invoice.
     * Used by the frontend to show how much is still owed.
     */
    @Transactional(readOnly = true)
    public BigDecimal getOutstandingBalance(Long invoiceId) {
        BusinessInvoice invoice = invoiceService.getEntityById(invoiceId);
        BigDecimal paid = paymentRepository.sumAmountByInvoiceId(invoiceId);
        return invoice.getTotalAmount().subtract(paid).max(BigDecimal.ZERO);
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void firePaymentEvent(BusinessInvoice invoice, Customer customer,
                                  BusinessPayment payment) {
        try {
            Long tenantId     = SecurityUtils.getCurrentTenantId();
            String tenantName = tenantService.getTenantNameById(tenantId);

            // Compute outstanding balance for the payment-received email
            BigDecimal totalPaid    = paymentRepository.sumAmountByInvoiceId(invoice.getId());
            BigDecimal outstanding  = invoice.getTotalAmount()
                    .subtract(totalPaid != null ? totalPaid : BigDecimal.ZERO)
                    .max(BigDecimal.ZERO);

            java.util.Map<String, Object> extra = new java.util.HashMap<>();
            extra.put("invoiceNumber",     invoice.getInvoiceNumber());
            extra.put("amountPaid",        payment.getAmount().toPlainString());
            extra.put("currency",          invoice.getCurrency());
            extra.put("paidAt",            payment.getPaidAt().toString());
            extra.put("outstandingAmount", outstanding.toPlainString());

            outboxEventPublisher.save(BillingEvent.builder()
                    .eventType(NotificationEvent.BUSINESS_PAYMENT_RECORDED)
                    .tenantId(tenantId)
                    .tenantName(tenantName)
                    .recipientEmail(customer.getEmail())
                    .recipientName(customer.getName())
                    .invoiceNumber(invoice.getInvoiceNumber())
                    .invoiceAmount(invoice.getTotalAmount())
                    .build(), "BusinessInvoice", invoice.getId());

        } catch (Exception e) {
            log.warn("Failed to fire BUSINESS_PAYMENT_RECORDED for invoiceId={}: {}",
                    invoice.getId(), e.getMessage());
        }
    }

}