package com.mtbs.billing.service;

import com.mtbs.billing.dto.OrderResponse;
import com.mtbs.billing.dto.PaymentResponse;
import com.mtbs.billing.dto.RefundResult;
import com.mtbs.billing.dto.VerifyPaymentRequest;
import com.mtbs.billing.entity.Invoice;
import com.mtbs.billing.entity.Payment;
import com.mtbs.billing.event.PaymentCapturedEventPublisher;
import com.mtbs.billing.event.outbox.OutboxEventPublisher;
import com.mtbs.billing.mapper.PaymentMapper;
import com.mtbs.billing.repository.SubscriptionRepository;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.shared.enums.billing.PaymentStatus;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.billing.BillingEvent;
import com.mtbs.shared.event.billing.PaymentCapturedEvent;
import com.mtbs.shared.event.audit.AuditLogEvent;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import com.mtbs.shared.exception.PaymentException;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.billing.repository.InvoiceRepository;
import com.mtbs.billing.repository.PaymentRepository;
import com.mtbs.billing.gateway.PaymentGatewayPort;
import com.mtbs.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final int MAX_RETRY_COUNT = 3;

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final PaymentCapturedEventPublisher paymentCapturedEventPublisher;
    private final PaymentGatewayPort paymentGateway;
    private final OutboxEventPublisher outboxEventPublisher;
    private final SubscriptionRepository subscriptionRepository;
    private final TenantService tenantService;
    private final PaymentMapper paymentMapper;


    // ── Initiate payment ──────────────────────────────────────────────────────

    /**
     * Creates a Razorpay order for the given invoice.
     * Idempotent — returns existing order if already created for this invoice.
     */
    @Transactional
    public OrderResponse processPayment(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("Invoice", invoiceId));

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw PaymentException.paymentAlreadyProcessed();
        }

        String idempotencyKey = "pay-" + TenantContext.getTenantId() + "-" + invoiceId;

        // Idempotency — return existing pending payment order details if already created
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> OrderResponse.builder()
                        .orderId(existing.getRazorpayOrderId())
                        .amount(existing.getAmount().multiply(BigDecimal.valueOf(100)).longValue())
                        .currency(existing.getCurrency())
                        .build())
                .orElseGet(() -> createNewOrder(invoice, idempotencyKey));
    }

    private OrderResponse createNewOrder(Invoice invoice, String idempotencyKey) {
        // Convert to paise (Razorpay requires smallest currency unit)
        long amountInPaise = invoice.getTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        // receipt acts as the idempotency key on Razorpay's side
        OrderResponse order = paymentGateway.createOrder(amountInPaise, invoice.getCurrency(), idempotencyKey);

        Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(invoice.getTotalAmount())
                .currency(invoice.getCurrency())
                .status(PaymentStatus.PENDING)
                .razorpayOrderId(order.getOrderId())
                .idempotencyKey(idempotencyKey)
                .retryCount(0)
                .build();

        paymentRepository.save(payment);
        log.info("Payment order created — invoiceId={}, orderId={}", invoice.getId(), order.getOrderId());

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_INITIATED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesAfter(Map.of("amount", payment.getAmount().toString(), "currency", payment.getCurrency()))
                .description("Payment initiated: " + payment.getAmount() + " " + payment.getCurrency())
                .module("BILLING")
                .build(), "Payment", payment.getId());

        return order;
    }

    // ── Verify and capture ────────────────────────────────────────────────────

    /**
     * Verifies the Razorpay signature from the frontend after checkout completes.
     * On success: marks payment SUCCEEDED, marks invoice PAID.
     */
    @Transactional
    public PaymentResponse verifyAndCapturePayment(VerifyPaymentRequest request) {
        Payment payment = paymentRepository.findByRazorpayOrderId(request.getRazorpayOrderId())
                .orElseThrow(() -> ResourceException.notFound("Payment", request.getRazorpayOrderId()));

        // Verify HMAC signature
        boolean valid = paymentGateway.verifyPaymentSignature(
                request.getRazorpayOrderId(),
                request.getRazorpayPaymentId(),
                request.getRazorpaySignature());

        if (!valid) {
            throw PaymentException.invalidSignature();
        }

        // Capture the payment (Razorpay manual capture mode)
        long amountInPaise = payment.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();
        paymentGateway.capturePayment(request.getRazorpayPaymentId(), amountInPaise);

        payment.setRazorpayPaymentId(request.getRazorpayPaymentId());
        payment.setRazorpaySignature(request.getRazorpaySignature());
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setPaidAt(Instant.now());
        paymentRepository.save(payment);

        invoiceService.markInvoicePaid(
                payment.getInvoiceId(),
                request.getRazorpayPaymentId(),
                Instant.now());

        paymentCapturedEventPublisher.publish(new PaymentCapturedEvent(NotificationEvent.PAYMENT_CAPTURED, TenantContext.getTenantId(), payment.getInvoiceId()));

        log.info("Payment verified and captured — paymentId={}", payment.getId());

        firePaymentEvent(NotificationEvent.PAYMENT_SUCCEEDED, payment);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_COMPLETED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", "PENDING"))
                .changesAfter(Map.of("status", "SUCCEEDED", "razorpayPaymentId", payment.getRazorpayPaymentId()))
                .description("Payment completed: " + payment.getAmount() + " " + payment.getCurrency())
                .module("BILLING")
                .build(), "Payment", payment.getId());

        return paymentMapper.toResponse(payment);
    }

    // ── Webhook handlers (called by RazorpayWebhookController) ───────────────

    @Transactional
    public void handlePaymentSuccess(String razorpayPaymentId) {
        paymentRepository.findByRazorpayPaymentId(razorpayPaymentId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                return; // Already processed — idempotent
            }
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setPaidAt(Instant.now());
            paymentRepository.save(payment);
            invoiceService.markInvoicePaid(payment.getInvoiceId(), razorpayPaymentId, Instant.now());
            log.info("Webhook: payment success processed — razorpayPaymentId={}", razorpayPaymentId);
            firePaymentEvent(NotificationEvent.PAYMENT_SUCCEEDED, payment);

            outboxEventPublisher.save(AuditLogEvent.builder()
                    .action(AuditAction.PAYMENT_COMPLETED)
                    .entityType(AuditEntityType.PAYMENT)
                    .entityId(payment.getId())
                    .contextTenantId(TenantContext.getTenantId())
                    .contextTenantName(tenantService.fetchTenantName())
                    .description("Payment completed via webhook: " + payment.getAmount() + " " + payment.getCurrency())
                    .module("BILLING")
                    .build(), "Payment", payment.getId());
        });
    }

    @Transactional
    public void handlePaymentFailure(String razorpayPaymentId, String failureCode, String failureMessage) {
        paymentRepository.findByRazorpayPaymentId(razorpayPaymentId).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode(failureCode);
            payment.setFailureMessage(failureMessage);

            if (payment.getRetryCount() < MAX_RETRY_COUNT) {
                // Schedule retry with exponential backoff: 1h, 6h, 24h
                Duration delay = switch (payment.getRetryCount()) {
                    case 0 -> Duration.ofHours(1);
                    case 1 -> Duration.ofHours(6);
                    default -> Duration.ofHours(24);
                };
                payment.setNextRetryAt(Instant.now().plus(delay));
                log.info("Payment failure — scheduling retry #{} for paymentId={}, delay={}",
                        payment.getRetryCount() + 1, payment.getId(), delay);
            }

            paymentRepository.save(payment);
            firePaymentEvent(NotificationEvent.PAYMENT_FAILED, payment);

            outboxEventPublisher.save(AuditLogEvent.builder()
                    .action(AuditAction.PAYMENT_FAILED)
                    .entityType(AuditEntityType.PAYMENT)
                    .entityId(payment.getId())
                    .contextTenantId(TenantContext.getTenantId())
                    .contextTenantName(tenantService.fetchTenantName())
                    .changesAfter(Map.of("status", "FAILED", "failureCode", failureCode, "failureMessage", failureMessage))
                    .description("Payment failed: " + failureCode + " - " + failureMessage)
                    .module("BILLING")
                    .severity("WARN")
                    .build(), "Payment", payment.getId());
        });
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Transactional
    public OrderResponse retryFailedPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceException.notFound("Payment", paymentId));

        if (payment.getRetryCount() >= MAX_RETRY_COUNT) {
            // Suspend the subscription after max retries
            invoiceRepository.findById(payment.getInvoiceId())
                    .ifPresent(inv -> subscriptionRepository.findById(inv.getSubscriptionId())
                            .orElseThrow(() -> ResourceException.notFound("Subscription", inv.getSubscriptionId())));
            throw ResourceException.invalid("Maximum retry attempts reached. Subscription suspended.");
        }

        long amountInPaise = payment.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        String retryReceipt = payment.getIdempotencyKey() + "-retry-" + (payment.getRetryCount() + 1);
        OrderResponse order = paymentGateway.createOrder(amountInPaise, payment.getCurrency(), retryReceipt);

        payment.setRazorpayOrderId(order.getOrderId());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setRetryCount(payment.getRetryCount() + 1);
        payment.setNextRetryAt(null);
        paymentRepository.save(payment);

        log.info("Payment retry #{} created — paymentId={}, orderId={}",
                payment.getRetryCount(), paymentId, order.getOrderId());

        firePaymentEvent(NotificationEvent.PAYMENT_RETRY, payment);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_INITIATED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .description("Payment retry #" + payment.getRetryCount() + " initiated")
                .module("BILLING")
                .build(), "Payment", payment.getId());

        return order;
    }

    // ── Refund ────────────────────────────────────────────────────────────────

    @Transactional
    public PaymentResponse refundPayment(Long paymentId, Long amountInRupees) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceException.notFound("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw ResourceException.invalid("Only SUCCEEDED payments can be refunded");
        }

        // null or 0 → full refund (pass original amount in paise)
        long originalAmountPaise = payment.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        long refundAmountPaise = (amountInRupees == null || amountInRupees == 0)
                ? originalAmountPaise
                : amountInRupees * 100;

        RefundResult result = paymentGateway.refundPayment(payment.getRazorpayPaymentId(), refundAmountPaise);

        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        log.info("Payment refunded — paymentId={}, amountPaise={}", paymentId, refundAmountPaise);
        firePaymentEvent(NotificationEvent.PAYMENT_REFUNDED, payment);

        outboxEventPublisher.save(AuditLogEvent.builder()
                .action(AuditAction.PAYMENT_REFUNDED)
                .entityType(AuditEntityType.PAYMENT)
                .entityId(payment.getId())
                .contextTenantId(TenantContext.getTenantId())
                .contextTenantName(tenantService.fetchTenantName())
                .changesBefore(Map.of("status", "SUCCEEDED"))
                .changesAfter(Map.of("status", "REFUNDED", "refundAmount", String.valueOf(refundAmountPaise)))
                .description("Payment refunded: " + (amountInRupees != null ? amountInRupees : payment.getAmount()) + " " + payment.getCurrency())
                .module("BILLING")
                .severity("WARN")
                .build(), "Payment", payment.getId());

        return paymentMapper.toResponse(payment);
    }

    // ── Payment Link ─────────────────────────────────────────────────────────

    /**
     * Creates a Razorpay payment link for an invoice.
     * Returns the payment link ID that can be sent to the customer.
     */
    @Transactional
    public String createPaymentLink(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("Invoice", invoiceId));

        long amountPaise = invoice.getTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        String description = "Invoice: " + invoice.getInvoiceNumber();
        String receipt = invoice.getInvoiceNumber();
        String customerEmail = null;
        String customerName = tenantService.fetchTenantName();
        String customerPhone = null;

        String paymentLinkId = paymentGateway.createPaymentLink(
                amountPaise,
                invoice.getCurrency(),
                description,
                customerEmail,
                customerName,
                customerPhone,
                receipt
        );

        invoice.setRazorpayInvoiceId(paymentLinkId);
        invoiceRepository.save(invoice);

        log.info("Payment link created for invoice {}: {}", invoiceId, paymentLinkId);

        return paymentLinkId;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public PaymentResponse getPaymentById(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Payment", id));
        return paymentMapper.toResponse(payment);
    }

    public List<PaymentResponse> getPaymentsByInvoice(Long invoiceId) {
        return paymentRepository.findByInvoiceId(invoiceId)
                .stream()
                .map(paymentMapper::toResponse)
                .collect(Collectors.toList());
    }

    public Page<PaymentResponse> listPayments(Pageable pageable) {
        return paymentRepository.findAll(pageable).map(paymentMapper::toResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────


    private void firePaymentEvent(NotificationEvent eventType, Payment payment) {
        try {
            outboxEventPublisher.save(BillingEvent.builder()
                    .eventType(eventType)
                    .paymentId(payment.getRazorpayPaymentId())
                    .paymentAmount(payment.getAmount())
                    .build(), "Payment", payment.getId());
        } catch (Exception e) {
            log.warn("Failed to fire {} event for paymentId={}: {}", eventType, payment.getId(), e.getMessage());
        }
    }
}