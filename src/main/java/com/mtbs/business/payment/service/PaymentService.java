package com.mtbs.business.payment.service;

import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.business.invoice.service.BillService;
import com.mtbs.business.payment.dto.CustomerOutstandingResponse;
import com.mtbs.business.payment.dto.CustomerPaymentResponse;
import com.mtbs.business.payment.dto.PaymentResponse;
import com.mtbs.business.payment.dto.RecordCustomerPaymentRequest;
import com.mtbs.business.payment.dto.RecordPaymentRequest;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.payment.entity.Payment;
import com.mtbs.business.payment.mapper.PaymentMapper;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.shared.enums.bill.InvoiceStatus;
import com.mtbs.shared.enums.bill.PaymentMethod;
import com.mtbs.shared.enums.bill.PaymentStatus;
import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.bill.BillEvent;
import com.mtbs.shared.event.outbox.OutboxEventPublisher;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.business.payment.repository.PaymentRepository;
import com.mtbs.tenant.service.ShopService;
import com.mtbs.shared.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Records payments received from customers and manages payment links.
 *
 * Partial payment support:
 *   Multiple payments can exist per invoice. The invoice transitions to PAID
 *   only when sum(payments) >= invoice.totalAmount. This handles scenarios
 *   like a customer paying 50% upfront and 50% on delivery.
 *
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BillService invoiceService;
    private final CustomerService customerService;
    private final ShopService tenantService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final PaymentMapper paymentMapper;

    // ── Record payment ────────────────────────────────────────────────────────

    /**
     * Records a payment received from a customer.
     *
     * Rules:
     *  - Invoice must be OPEN (not DRAFT, not already PAID, not VOID)
     *  - Amount must be > 0
     *  - Amount must not exceed the outstanding (CONFIRMED-only) balance
     *  - method = CREDIT is inserted PENDING — a promise, not collected cash
     *    — and never marks the invoice PAID, no matter the amount.
     *  - Every other method is inserted CONFIRMED; if total CONFIRMED
     *    payments collected >= invoice.totalAmount → mark invoice PAID.
     *
     * Supports partial payments — call multiple times until fully paid.
     * Takes a row lock on the invoice for the duration of this transaction
     * so it can't race a concurrent recordForCustomer() FIFO allocation
     * touching the same bill.
     */
    @Transactional
    public PaymentResponse record(Long invoiceId, RecordPaymentRequest request) {
        Bill invoice = invoiceService.getEntityByIdForUpdate(invoiceId);

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

        boolean isCredit = request.getMethod() == PaymentMethod.CREDIT;

        Payment payment = Payment.builder()
                .invoiceId(invoiceId)
                .amount(request.getAmount())
                .currency(invoice.getCurrency())
                .method(request.getMethod())
                .status(isCredit ? PaymentStatus.PENDING : PaymentStatus.CONFIRMED)
                .notes(request.getNotes())
                .paidAt(request.getPaidAt() != null ? request.getPaidAt() : Instant.now())
                .build();

        Payment saved = paymentRepository.save(payment);
        log.info("Payment recorded — id={}, invoiceId={}, amount={}, method={}, status={}",
                saved.getId(), invoiceId, saved.getAmount(), saved.getMethod(), saved.getStatus());

        // A pending credit promise never completes the invoice — only a
        // CONFIRMED payment that covers the total does.
        if (!isCredit) {
            BigDecimal totalPaid = alreadyPaid.add(request.getAmount());
            if (totalPaid.compareTo(invoice.getTotalAmount()) >= 0) {
                invoiceService.markPaid(invoiceId);
                log.info("Invoice fully paid — invoiceId={}, totalCollected={}", invoiceId, totalPaid);

                Customer customer = customerService.getEntityById(invoice.getCustomerId());
                firePaymentEvent(invoice, customer, saved);
            }
        }

        return paymentMapper.toResponse(saved);
    }

    /**
     * Confirms a PENDING credit payment — "the customer actually paid me
     * back what they owed on credit." Flips it to CONFIRMED and re-runs the
     * same "is the invoice now fully paid" check record() performs.
     *
     * Rejects if the invoice was already PAID by other payments in the
     * meantime (the shopkeeper should cancel/replace this pending payment
     * instead of confirming a promise that's no longer needed).
     */
    @Transactional
    public PaymentResponse confirmPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ResourceException.notFound("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw ResourceException.invalid(
                "Only a pending payment can be confirmed. Current status: " + payment.getStatus());
        }

        Bill invoice = invoiceService.getEntityByIdForUpdate(payment.getInvoiceId());
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw ResourceException.invalid(
                "This bill was already fully paid by other payments — there's nothing left to confirm this against.");
        }

        payment.setStatus(PaymentStatus.CONFIRMED);
        Payment saved = paymentRepository.save(payment);
        log.info("Payment confirmed — id={}, invoiceId={}, amount={}", saved.getId(), invoice.getId(), saved.getAmount());

        BigDecimal totalPaid = paymentRepository.sumAmountByInvoiceId(invoice.getId());
        if (totalPaid.compareTo(invoice.getTotalAmount()) >= 0) {
            invoiceService.markPaid(invoice.getId());
            log.info("Invoice fully paid on confirm — invoiceId={}, totalCollected={}", invoice.getId(), totalPaid);

            Customer customer = customerService.getEntityById(invoice.getCustomerId());
            firePaymentEvent(invoice, customer, saved);
        }

        return paymentMapper.toResponse(saved);
    }

    // ── Customer-level FIFO payment allocation ──────────────────────────────────

    /**
     * Records one payment from a customer and allocates it across that
     * customer's OPEN bills oldest-first (FIFO) — for a shopkeeper who just
     * wants to say "this customer paid me ₹X" without picking a specific
     * bill. Kept entirely separate from record(): that method stays for
     * deliberately targeting one specific bill.
     *
     * Rules mirror record() per-bill (CREDIT → PENDING, never completes a
     * bill; everything else → CONFIRMED), plus:
     *  - Rejects if the customer has zero OPEN bills.
     *  - Rejects if amount exceeds the customer's total outstanding — no
     *    carry-forward account-credit concept exists; the shopkeeper
     *    re-enters the correct amount.
     *  - Locks every candidate bill for the transaction's duration (see
     *    BillRepository.findAllByCustomerIdAndStatusForUpdate) so this can't
     *    race a concurrent record() or another recordForCustomer() call.
     */
    @Transactional
    public CustomerPaymentResponse recordForCustomer(Long customerId, RecordCustomerPaymentRequest request) {
        Customer customer = customerService.getEntityById(customerId);

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw ResourceException.invalid("Payment amount must be greater than zero.");
        }

        List<Bill> openBills = invoiceService.getOpenBillsForCustomerForUpdate(customerId);
        if (openBills.isEmpty()) {
            throw ResourceException.invalid("This customer has no outstanding bills to pay.");
        }

        // Per-bill outstanding, computed once up front — safe to reuse through
        // the allocation loop below since every candidate bill is row-locked
        // for the rest of this transaction.
        List<BigDecimal> outstandingByBill = new ArrayList<>(openBills.size());
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        for (Bill bill : openBills) {
            BigDecimal billOutstanding = bill.getTotalAmount()
                    .subtract(paymentRepository.sumAmountByInvoiceId(bill.getId()));
            outstandingByBill.add(billOutstanding);
            totalOutstanding = totalOutstanding.add(billOutstanding);
        }

        if (request.getAmount().compareTo(totalOutstanding) > 0) {
            throw ResourceException.invalid(String.format(
                "Payment amount ₹%.2f exceeds this customer's total outstanding balance ₹%.2f.",
                request.getAmount(), totalOutstanding));
        }

        boolean isCredit = request.getMethod() == PaymentMethod.CREDIT;
        UUID groupId = UUID.randomUUID();
        Instant paidAt = request.getPaidAt() != null ? request.getPaidAt() : Instant.now();

        BigDecimal remaining = request.getAmount();
        List<Payment> savedPayments = new ArrayList<>();
        int billsCompleted = 0;

        for (int i = 0; i < openBills.size() && remaining.compareTo(BigDecimal.ZERO) > 0; i++) {
            Bill bill = openBills.get(i);
            BigDecimal billOutstanding = outstandingByBill.get(i);
            if (billOutstanding.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal allocate = remaining.min(billOutstanding);

            Payment payment = Payment.builder()
                    .invoiceId(bill.getId())
                    .amount(allocate)
                    .currency(bill.getCurrency())
                    .method(request.getMethod())
                    .status(isCredit ? PaymentStatus.PENDING : PaymentStatus.CONFIRMED)
                    .notes(request.getNotes())
                    .paidAt(paidAt)
                    .paymentGroupId(groupId)
                    .build();
            Payment saved = paymentRepository.save(payment);
            savedPayments.add(saved);

            // A pending credit promise never completes a bill.
            if (!isCredit && allocate.compareTo(billOutstanding) >= 0) {
                invoiceService.markPaid(bill.getId());
                billsCompleted++;
                firePaymentEvent(bill, customer, saved);
            }

            remaining = remaining.subtract(allocate);
        }

        log.info("Customer payment allocated — customerId={}, groupId={}, amount={}, billsTouched={}, billsCompleted={}",
                customerId, groupId, request.getAmount(), savedPayments.size(), billsCompleted);

        return CustomerPaymentResponse.builder()
                .paymentGroupId(groupId)
                .totalAmount(request.getAmount())
                .billsCompleted(billsCompleted)
                .payments(savedPayments.stream().map(paymentMapper::toResponse).collect(Collectors.toList()))
                .build();
    }

    /**
     * A customer's total outstanding balance plus the ordered per-bill
     * breakdown — lets the "Record Payment" UI preview how a customer-level
     * payment would apply before it's submitted. Read-only, no locking.
     */
    @Transactional(readOnly = true)
    public CustomerOutstandingResponse getCustomerOutstanding(Long customerId) {
        customerService.getEntityById(customerId); // validates the customer exists

        List<Bill> openBills = invoiceService.getOpenBillsForCustomer(customerId);
        List<CustomerOutstandingResponse.BillOutstandingItem> items = new ArrayList<>(openBills.size());
        BigDecimal totalOutstanding = BigDecimal.ZERO;

        for (Bill bill : openBills) {
            BigDecimal billOutstanding = bill.getTotalAmount()
                    .subtract(paymentRepository.sumAmountByInvoiceId(bill.getId()));
            totalOutstanding = totalOutstanding.add(billOutstanding);
            items.add(CustomerOutstandingResponse.BillOutstandingItem.builder()
                    .invoiceId(bill.getId())
                    .invoiceNumber(bill.getInvoiceNumber())
                    .totalAmount(bill.getTotalAmount())
                    .outstandingAmount(billOutstanding)
                    .createdAt(bill.getCreatedAt())
                    .dueDate(bill.getDueDate())
                    .build());
        }

        return CustomerOutstandingResponse.builder()
                .customerId(customerId)
                .totalOutstanding(totalOutstanding)
                .bills(items)
                .build();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PaymentResponse> listByInvoice(Long invoiceId) {
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
        Bill invoice = invoiceService.getEntityById(invoiceId);
        BigDecimal paid = paymentRepository.sumAmountByInvoiceId(invoiceId);
        return invoice.getTotalAmount().subtract(paid).max(BigDecimal.ZERO);
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void firePaymentEvent(Bill invoice, Customer customer,
                                  Payment payment) {
        try {
            Long tenantId = SecurityUtils.getCurrentTenantId();
            // ShopRepository.findById(null) throws IllegalArgumentException from
            // inside its own @Transactional advice — even though this whole method
            // is wrapped in try/catch, that exception marks the shared ambient
            // transaction rollback-only before reaching this catch block, silently
            // dooming the caller's payment to UnexpectedRollbackException at commit.
            // Guarding here keeps this genuinely best-effort, as intended.
            String tenantName = tenantId != null ? tenantService.getTenantNameById(tenantId) : "Unknown";

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

            outboxEventPublisher.save(BillEvent.builder()
                    .eventType(NotificationEvent.PAYMENT_RECORDED)
                    .tenantId(tenantId)
                    .tenantName(tenantName)
                    .recipientEmail(customer.getEmail())
                    .recipientName(customer.getName())
                    .invoiceNumber(invoice.getInvoiceNumber())
                    .invoiceAmount(invoice.getTotalAmount())
                    .build(), "Bill", invoice.getId());

        } catch (Exception e) {
            log.warn("Failed to fire PAYMENT_RECORDED for invoiceId={}: {}",
                    invoice.getId(), e.getMessage());
        }
    }

}