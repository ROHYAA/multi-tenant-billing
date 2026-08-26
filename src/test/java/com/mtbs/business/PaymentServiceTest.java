package com.mtbs.business;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.customer.repository.CustomerRepository;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.repository.BillRepository;
import com.mtbs.business.invoice.service.BillService;
import com.mtbs.business.payment.dto.CustomerOutstandingResponse;
import com.mtbs.business.payment.dto.CustomerPaymentResponse;
import com.mtbs.business.payment.dto.PaymentResponse;
import com.mtbs.business.payment.dto.RecordCustomerPaymentRequest;
import com.mtbs.business.payment.dto.RecordPaymentRequest;
import com.mtbs.business.payment.entity.Payment;
import com.mtbs.business.payment.repository.PaymentRepository;
import com.mtbs.business.payment.service.PaymentService;
import com.mtbs.shared.enums.bill.InvoiceStatus;
import com.mtbs.shared.enums.bill.PaymentMethod;
import com.mtbs.shared.enums.bill.PaymentStatus;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("PaymentService Integration Tests")
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private BillService billService;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    private String currentSchema;
    private Long customerId;

    @BeforeEach
    void setUp() {
        currentSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(currentSchema);

        customerId = customerRepository.save(Customer.builder()
            .name("Test Customer")
            .email("customer@test.com")
            .build()).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(currentSchema);
    }

    private Bill openBill(String invoiceNumber, String total) {
        return billRepository.save(Bill.builder()
            .invoiceNumber(invoiceNumber)
            .status(InvoiceStatus.OPEN)
            .subtotal(new BigDecimal(total))
            .taxAmount(BigDecimal.ZERO)
            .totalAmount(new BigDecimal(total))
            .currency("INR")
            .customerId(customerId)
            .build());
    }

    private RecordPaymentRequest paymentRequest(String amount, PaymentMethod method) {
        return RecordPaymentRequest.builder()
            .amount(new BigDecimal(amount))
            .method(method)
            .paidAt(Instant.now())
            .build();
    }

    @Nested
    @DisplayName("record — credit stays pending")
    class RecordCreditTests {

        @Test
        @DisplayName("CREDIT payment is inserted PENDING and does not mark the bill PAID, even covering the full amount")
        void record_credit_insertedPendingAndDoesNotSettle() {
            Bill bill = openBill("BINV-CR-001", "500");

            PaymentResponse response = paymentService.record(bill.getId(), paymentRequest("500", PaymentMethod.CREDIT));

            assertEquals(PaymentStatus.PENDING, response.getStatus());

            Bill updated = billRepository.findById(bill.getId()).orElseThrow();
            assertEquals(InvoiceStatus.OPEN, updated.getStatus(), "a pending credit payment must not settle the bill");
        }

        @Test
        @DisplayName("CASH payment is inserted CONFIRMED and marks the bill PAID when it covers the total")
        void record_cash_confirmedAndSettles() {
            Bill bill = openBill("BINV-CR-002", "500");

            PaymentResponse response = paymentService.record(bill.getId(), paymentRequest("500", PaymentMethod.CASH));

            assertEquals(PaymentStatus.CONFIRMED, response.getStatus());

            Bill updated = billRepository.findById(bill.getId()).orElseThrow();
            assertEquals(InvoiceStatus.PAID, updated.getStatus());
        }

        @Test
        @DisplayName("outstanding balance excludes PENDING credit payments")
        void getOutstandingBalance_excludesPendingCredit() {
            Bill bill = openBill("BINV-CR-003", "500");
            paymentService.record(bill.getId(), paymentRequest("500", PaymentMethod.CREDIT));

            BigDecimal outstanding = paymentService.getOutstandingBalance(bill.getId());

            assertEquals(0, new BigDecimal("500").compareTo(outstanding),
                "a pending credit payment must not reduce the computed outstanding balance");
        }
    }

    @Nested
    @DisplayName("confirmPayment")
    class ConfirmPaymentTests {

        @Test
        @DisplayName("confirming a pending credit payment that covers the total settles the bill")
        void confirmPayment_completesTheBill() {
            Bill bill = openBill("BINV-CF-001", "300");
            PaymentResponse pending = paymentService.record(bill.getId(), paymentRequest("300", PaymentMethod.CREDIT));

            PaymentResponse confirmed = paymentService.confirmPayment(pending.getId());

            assertEquals(PaymentStatus.CONFIRMED, confirmed.getStatus());
            Bill updated = billRepository.findById(bill.getId()).orElseThrow();
            assertEquals(InvoiceStatus.PAID, updated.getStatus());
        }

        @Test
        @DisplayName("confirming a non-pending payment throws")
        void confirmPayment_notPending_throws() {
            Bill bill = openBill("BINV-CF-002", "300");
            PaymentResponse confirmed = paymentService.record(bill.getId(), paymentRequest("300", PaymentMethod.CASH));

            assertThrows(ResourceException.class, () -> paymentService.confirmPayment(confirmed.getId()));
        }

        @Test
        @DisplayName("confirming against a bill already fully paid by other payments throws")
        void confirmPayment_billAlreadyPaid_throws() {
            Bill bill = openBill("BINV-CF-003", "300");
            PaymentResponse pending = paymentService.record(bill.getId(), paymentRequest("300", PaymentMethod.CREDIT));

            // Bill was never actually settled by the pending credit — void it out of
            // the way and directly flip it to PAID to simulate "settled by something
            // else in the meantime" without fighting the CREDIT/PENDING semantics.
            Bill entity = billRepository.findById(bill.getId()).orElseThrow();
            entity.setStatus(InvoiceStatus.PAID);
            billRepository.save(entity);

            assertThrows(ResourceException.class, () -> paymentService.confirmPayment(pending.getId()));
        }
    }

    @Nested
    @DisplayName("recordForCustomer — FIFO allocation")
    class RecordForCustomerTests {

        @Test
        @DisplayName("allocates oldest-first across two bills, fully paying the first and partially the second")
        void recordForCustomer_allocatesOldestFirst() throws InterruptedException {
            Bill first = openBill("BINV-FIFO-001", "200");
            Thread.sleep(5); // guarantee distinct createdAt ordering
            Bill second = openBill("BINV-FIFO-002", "300");

            CustomerPaymentResponse response = paymentService.recordForCustomer(customerId,
                RecordCustomerPaymentRequest.builder()
                    .amount(new BigDecimal("250"))
                    .method(PaymentMethod.CASH)
                    .paidAt(Instant.now())
                    .build());

            assertEquals(1, response.getBillsCompleted());
            assertEquals(2, response.getPayments().size());
            assertEquals(0, new BigDecimal("250").compareTo(response.getTotalAmount()));

            Bill firstAfter  = billRepository.findById(first.getId()).orElseThrow();
            Bill secondAfter = billRepository.findById(second.getId()).orElseThrow();
            assertEquals(InvoiceStatus.PAID, firstAfter.getStatus(), "the older bill should be fully settled first");
            assertEquals(InvoiceStatus.OPEN, secondAfter.getStatus(), "the newer bill should still be open");

            BigDecimal secondOutstanding = paymentService.getOutstandingBalance(second.getId());
            assertEquals(0, new BigDecimal("250").compareTo(secondOutstanding),
                "300 total - 50 allocated from the 250 payment = 250 still outstanding");
        }

        @Test
        @DisplayName("all payments from one call share a payment_group_id")
        void recordForCustomer_sharesPaymentGroupId() throws InterruptedException {
            openBill("BINV-FIFO-010", "100");
            Thread.sleep(5);
            openBill("BINV-FIFO-011", "100");

            CustomerPaymentResponse response = paymentService.recordForCustomer(customerId,
                RecordCustomerPaymentRequest.builder()
                    .amount(new BigDecimal("150"))
                    .method(PaymentMethod.UPI)
                    .paidAt(Instant.now())
                    .build());

            assertEquals(2, response.getPayments().size());
            assertNotNull(response.getPaymentGroupId());
            response.getPayments().forEach(p -> {
                Payment saved = paymentRepository.findById(p.getId()).orElseThrow();
                assertEquals(response.getPaymentGroupId(), saved.getPaymentGroupId());
            });
        }

        @Test
        @DisplayName("CREDIT allocates as PENDING on every touched bill and never completes them")
        void recordForCustomer_credit_allocatesPendingOnly() throws InterruptedException {
            Bill first = openBill("BINV-FIFO-020", "100");
            Thread.sleep(5);
            Bill second = openBill("BINV-FIFO-021", "100");

            CustomerPaymentResponse response = paymentService.recordForCustomer(customerId,
                RecordCustomerPaymentRequest.builder()
                    .amount(new BigDecimal("200"))
                    .method(PaymentMethod.CREDIT)
                    .paidAt(Instant.now())
                    .build());

            assertEquals(0, response.getBillsCompleted());
            response.getPayments().forEach(p -> assertEquals(PaymentStatus.PENDING, p.getStatus()));

            assertEquals(InvoiceStatus.OPEN, billRepository.findById(first.getId()).orElseThrow().getStatus());
            assertEquals(InvoiceStatus.OPEN, billRepository.findById(second.getId()).orElseThrow().getStatus());
        }

        @Test
        @DisplayName("rejects an amount exceeding the customer's total outstanding balance")
        void recordForCustomer_overpayment_throws() {
            openBill("BINV-FIFO-030", "100");

            RecordCustomerPaymentRequest request = RecordCustomerPaymentRequest.builder()
                .amount(new BigDecimal("150"))
                .method(PaymentMethod.CASH)
                .paidAt(Instant.now())
                .build();

            assertThrows(ResourceException.class, () -> paymentService.recordForCustomer(customerId, request));
        }

        @Test
        @DisplayName("rejects when the customer has no OPEN bills")
        void recordForCustomer_noOpenBills_throws() {
            RecordCustomerPaymentRequest request = RecordCustomerPaymentRequest.builder()
                .amount(new BigDecimal("50"))
                .method(PaymentMethod.CASH)
                .paidAt(Instant.now())
                .build();

            assertThrows(ResourceException.class, () -> paymentService.recordForCustomer(customerId, request));
        }
    }

    @Nested
    @DisplayName("getCustomerOutstanding")
    class GetCustomerOutstandingTests {

        @Test
        @DisplayName("sums outstanding across every OPEN bill, oldest-first")
        void getCustomerOutstanding_sumsAcrossOpenBills() throws InterruptedException {
            openBill("BINV-OUT-001", "100");
            Thread.sleep(5);
            openBill("BINV-OUT-002", "150");

            CustomerOutstandingResponse response = paymentService.getCustomerOutstanding(customerId);

            assertEquals(0, new BigDecimal("250").compareTo(response.getTotalOutstanding()));
            assertEquals(2, response.getBills().size());
            assertEquals("BINV-OUT-001", response.getBills().get(0).getInvoiceNumber(),
                "oldest bill must be first");
        }
    }

    @Nested
    @DisplayName("voidInvoice — pending payment guard")
    class VoidWithPendingPaymentTests {

        @Test
        @DisplayName("voiding a bill with a pending credit payment throws")
        void voidInvoice_withPendingCreditPayment_throws() {
            Bill bill = openBill("BINV-VOID-001", "100");
            paymentService.record(bill.getId(), paymentRequest("100", PaymentMethod.CREDIT));

            assertThrows(ResourceException.class, () -> billService.voidInvoice(bill.getId()));
        }
    }
}
