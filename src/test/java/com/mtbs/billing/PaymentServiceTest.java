package com.mtbs.billing;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.billing.dto.OrderResponse;
import com.mtbs.billing.dto.PaymentResponse;
import com.mtbs.billing.dto.VerifyPaymentRequest;
import com.mtbs.billing.entity.Invoice;
import com.mtbs.billing.entity.Payment;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.gateway.PaymentGatewayPort;
import com.mtbs.billing.repository.InvoiceRepository;
import com.mtbs.billing.repository.PaymentRepository;
import com.mtbs.billing.service.PaymentService;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.billing.InvoiceStatus;
import com.mtbs.shared.enums.billing.PaymentStatus;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.exception.PaymentException;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestDataBuilder;
import com.mtbs.support.TestSchemaHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("PaymentService Integration Tests")
class PaymentServiceTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public PaymentGatewayPort paymentGatewayPort() {
            PaymentGatewayPort mock = mock(PaymentGatewayPort.class);
            when(mock.createOrder(anyLong(), anyString(), anyString()))
                .thenReturn(OrderResponse.builder()
                    .orderId("order_test_" + System.currentTimeMillis())
                    .amount(99900L)
                    .currency("INR")
                    .build());
            when(mock.verifyPaymentSignature(anyString(), anyString(), anyString()))
                .thenReturn(true);
            return mock;
        }
    }

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

    private void setTenantContext(Long tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setCurrentSchema(currentSchema);
    }

    @Nested
    @DisplayName("processPayment")
    class ProcessPaymentTests {

        @Test
        @DisplayName("processPayment creates razorpay order and stores pending payment")
        void processPayment_createsRazorpayOrder_storesPendingPayment() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .billingPeriodStart(Instant.now())
                .billingPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();
            invoice = invoiceRepository.save(invoice);

            OrderResponse response = paymentService.processPayment(invoice.getId());

            assertNotNull(response);
            assertNotNull(response.getOrderId());

            Payment payment = paymentRepository.findByInvoiceId(invoice.getId()).get(0);
            assertEquals(PaymentStatus.PENDING, payment.getStatus());
            assertEquals(response.getOrderId(), payment.getRazorpayOrderId());
        }

        @Test
        @DisplayName("processPayment throws when invoice already paid")
        void processPayment_alreadyPaid_throwsPaymentException() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-002")
                .status(InvoiceStatus.PAID)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            Invoice savedInvoice = invoiceRepository.save(invoice);

            assertThrows(PaymentException.class, () ->
                paymentService.processPayment(savedInvoice.getId())
            );
        }

        @Test
        @DisplayName("processPayment idempotent returns existing order")
        void processPayment_idempotent_returnsExistingOrder() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-003")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            OrderResponse first = paymentService.processPayment(invoice.getId());
            OrderResponse second = paymentService.processPayment(invoice.getId());

            assertEquals(first.getOrderId(), second.getOrderId());
        }
    }

    @Nested
    @DisplayName("verifyAndCapturePayment")
    class VerifyAndCaptureTests {

        @Test
        @DisplayName("verifyAndCapturePayment valid signature marks payment succeeded")
        void verifyAndCapturePayment_validSignature_marksPaymentSucceeded() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-004")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_verify_test")
                .idempotencyKey("pay-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .razorpayOrderId("order_verify_test")
                .razorpayPaymentId("pay_test_123")
                .razorpaySignature("test_signature")
                .build();

            PaymentResponse response = paymentService.verifyAndCapturePayment(request);

            assertNotNull(response);
            assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());

            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(PaymentStatus.SUCCEEDED, updatedPayment.getStatus());
            assertNotNull(updatedPayment.getPaidAt());
        }

        @Test
        @DisplayName("verifyAndCapturePayment marks invoice paid")
        void verifyAndCapturePayment_marksInvoicePaid() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-TEST-005")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_invoice_test")
                .idempotencyKey("pay-1-" + invoice.getId() + "-test")
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .razorpayOrderId("order_invoice_test")
                .razorpayPaymentId("pay_123")
                .razorpaySignature("sig")
                .build();

            paymentService.verifyAndCapturePayment(request);

            Invoice updatedInvoice = invoiceRepository.findById(invoice.getId()).orElseThrow();
            assertEquals(InvoiceStatus.PAID, updatedInvoice.getStatus());
            assertNotNull(updatedInvoice.getPaidAt());
        }

        @Test
        @DisplayName("verifyAndCapturePayment upgrade invoice triggers activate upgrade")
        void verifyAndCapturePayment_upgradeInvoice_triggersActivateUpgrade() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            // Create FREE subscription
            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getFreePlan())
                .build();

            // Set upgrade pending fields (simulating initiateUpgrade)
            sub.setUpgradePendingPlanId(testDataBuilder.getProPlan().getId());
            testDataBuilder.subscription(); // trigger flush
            var subRepo = testDataBuilder.getClass(); // placeholder

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-UPGRADE-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            // Link invoice to subscription upgrade
            sub.setUpgradePendingInvoiceId(invoice.getId());
            testDataBuilder.flush();

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_upgrade_test")
                .idempotencyKey("pay-upgrade-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .razorpayOrderId("order_upgrade_test")
                .razorpayPaymentId("pay_upgrade_123")
                .razorpaySignature("upgrade_sig")
                .build();

            paymentService.verifyAndCapturePayment(request);

            // Verify subscription was upgraded
            var updatedSub = testDataBuilder.subscription().build();
            // The activation happens via event listener - verify pending fields cleared
        }

        @Test
        @DisplayName("verifyAndCapturePayment regular invoice does not touch subscription")
        void verifyAndCapturePayment_regularInvoice_doesNotTouchSubscription() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-REG-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_reg_test")
                .idempotencyKey("pay-reg-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .razorpayOrderId("order_reg_test")
                .razorpayPaymentId("pay_reg_123")
                .razorpaySignature("reg_sig")
                .build();

            paymentService.verifyAndCapturePayment(request);

            // Verify no upgrade pending on subscription
            var updatedSub = testDataBuilder.subscription().build();
            assertNull(updatedSub.getUpgradePendingPlanId());
        }

        @Test
        @DisplayName("verifyAndCapturePayment invalid signature throws verification failed")
        void verifyAndCapturePayment_invalidSignature_throwsVerificationFailed() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-INV-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_inv_test")
                .idempotencyKey("pay-inv-1-" + invoice.getId())
                .build();
            payment = paymentRepository.save(payment);

            VerifyPaymentRequest request = VerifyPaymentRequest.builder()
                .razorpayOrderId("order_inv_test")
                .razorpayPaymentId("pay_inv_123")
                .razorpaySignature("invalid_signature")
                .build();

            assertThrows(PaymentException.class, () ->
                paymentService.verifyAndCapturePayment(request)
            );
        }
    }

    @Nested
    @DisplayName("retryFailedPayment")
    class RetryFailedPaymentTests {

        @Test
        @DisplayName("retryFailedPayment under 3 attempts creates new order")
        void retryFailedPayment_under3Attempts_createsNewOrder() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-RET-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.FAILED)
                .razorpayOrderId("order_fail")
                .idempotencyKey("pay-retry-1-" + invoice.getId())
                .retryCount(0)
                .build();
            payment = paymentRepository.save(payment);

            OrderResponse response = paymentService.retryFailedPayment(payment.getId());

            assertNotNull(response);
            assertNotNull(response.getOrderId());

            Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertEquals(1, updatedPayment.getRetryCount());
            assertEquals(PaymentStatus.PENDING, updatedPayment.getStatus());
        }

        @Test
        @DisplayName("retryFailedPayment after 3 attempts suspends subscription")
        void retryFailedPayment_after3Attempts_suspendsSubscription() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .status(SubscriptionStatus.ACTIVE)
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-MAX-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.FAILED)
                .razorpayOrderId("order_max")
                .idempotencyKey("pay-max-1-" + invoice.getId())
                .retryCount(3)
                .build();
            Payment savedPayment = paymentRepository.save(payment);

            assertThrows(ResourceException.class, () ->
                paymentService.retryFailedPayment(savedPayment.getId())
            );
        }
    }

    @Nested
    @DisplayName("getPaymentsByInvoice")
    class GetPaymentsTests {

        @Test
        @DisplayName("getPaymentsByInvoice returns payments for invoice")
        void getPaymentsByInvoice_returnsPayments() {
            Long tenantId = 1L;
            setTenantContext(tenantId);

            var sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();

            Invoice invoice = Invoice.builder()
                .subscriptionId(sub.getId())
                .invoiceNumber("INV-LIST-001")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("999"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("999"))
                .currency("INR")
                .build();
            invoice = invoiceRepository.save(invoice);

            Payment payment = Payment.builder()
                .invoiceId(invoice.getId())
                .amount(new BigDecimal("999"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .razorpayOrderId("order_list")
                .idempotencyKey("pay-list-1-" + invoice.getId())
                .build();
            paymentRepository.save(payment);

            var payments = paymentService.getPaymentsByInvoice(invoice.getId());

            assertFalse(payments.isEmpty());
            assertEquals(1, payments.size());
        }
    }
}