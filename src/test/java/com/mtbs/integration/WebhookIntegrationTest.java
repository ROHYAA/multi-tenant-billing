package com.mtbs.integration;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.billing.entity.Invoice;
import com.mtbs.billing.entity.Payment;
import com.mtbs.billing.repository.InvoiceRepository;
import com.mtbs.billing.repository.PaymentRepository;
import com.mtbs.billing.service.PaymentService;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.shared.enums.billing.PaymentStatus;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestDataBuilder;
import com.mtbs.support.TestSchemaHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Webhook Integration Tests")
class WebhookIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    @Autowired
    private TestDataBuilder testDataBuilder;

    private String currentSchema;

    @BeforeEach
    void setUp() {
        currentSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(currentSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(currentSchema);
    }

    @Nested
    @DisplayName("handlePaymentSuccess")
    class PaymentSuccessTests {

        @Test
        @DisplayName("handlePaymentSuccess marks invoice paid")
        void handlePaymentSuccess_marksInvoicePaid() {
            // Create invoice and payment
            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = invoiceRepository.save(Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-WEBHOOK-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build());

            Payment payment = paymentRepository.save(Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_webhook_001")
                .razorpayPaymentId("pay_webhook_001")
                .idempotencyKey("webhook-1-" + invoice.getId())
                .build());

            // Simulate webhook call - payment success
            paymentService.handlePaymentSuccess("pay_webhook_001");

            // Verify payment and invoice updated
            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(PaymentStatus.SUCCEEDED, updatedPayment.getStatus());
            assertNotNull(updatedPayment.getPaidAt());

            Invoice updatedInvoice = invoiceRepository.findById(invoice.getId()).orElseThrow();
            assertEquals(InvoiceStatus.PAID, updatedInvoice.getStatus());
            assertNotNull(updatedInvoice.getPaidAt());
        }

        @Test
        @DisplayName("handlePaymentSuccess idempotent - already processed")
        void handlePaymentSuccess_idempotent_noDoubleProcessing() {
            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = invoiceRepository.save(Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-WEBHOOK-002")
                .status(InvoiceStatus.PAID)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .paidAt(java.time.Instant.now())
                .build());

            Payment payment = paymentRepository.save(Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.SUCCEEDED)
                .razorpayOrderId("order_webhook_002")
                .razorpayPaymentId("pay_webhook_002")
                .idempotencyKey("webhook-2-" + invoice.getId())
                .paidAt(java.time.Instant.now())
                .build());

            // Call again - should be idempotent
            paymentService.handlePaymentSuccess("pay_webhook_002");

            // Verify still SUCCEEDED (no change)
            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(PaymentStatus.SUCCEEDED, updatedPayment.getStatus());
        }
    }

    @Nested
    @DisplayName("handlePaymentFailure")
    class PaymentFailureTests {

        @Test
        @DisplayName("handlePaymentFailure marks payment failed")
        void handlePaymentFailure_marksPaymentFailed() {
            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = invoiceRepository.save(Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-WEBHOOK-003")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build());

            Payment payment = paymentRepository.save(Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_webhook_003")
                .razorpayPaymentId("pay_webhook_003")
                .idempotencyKey("webhook-3-" + invoice.getId())
                .build());

            // Simulate webhook - payment failed
            paymentService.handlePaymentFailure("pay_webhook_003", "PAYMENT_DECLINED", "Card declined");

            // Verify payment marked as FAILED
            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(PaymentStatus.FAILED, updatedPayment.getStatus());
            assertEquals("PAYMENT_DECLINED", updatedPayment.getFailureCode());
            assertEquals("Card declined", updatedPayment.getFailureMessage());
            assertNotNull(updatedPayment.getNextRetryAt()); // Retry scheduled
        }

        @Test
        @DisplayName("handlePaymentFailure schedules retry")
        void handlePaymentFailure_schedulesRetry() {
            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = invoiceRepository.save(Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-WEBHOOK-004")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build());

            Payment payment = paymentRepository.save(Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_webhook_004")
                .razorpayPaymentId("pay_webhook_004")
                .idempotencyKey("webhook-4-" + invoice.getId())
                .retryCount(0)
                .build());

            paymentService.handlePaymentFailure("pay_webhook_004", "GATEWAY_ERROR", "Gateway timeout");

            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(PaymentStatus.FAILED, updatedPayment.getStatus());
            assertNotNull(updatedPayment.getNextRetryAt());
        }
    }
}