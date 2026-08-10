package com.mtbs.business;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.customer.repository.CustomerRepository;
import com.mtbs.business.invoice.dto.AddBillItemRequest;
import com.mtbs.business.invoice.dto.CreateBillRequest;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.business.invoice.repository.BillItemRepository;
import com.mtbs.business.invoice.repository.BillRepository;
import com.mtbs.business.invoice.service.BillService;
import com.mtbs.shared.enums.bill.InvoiceStatus;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("BillService Integration Tests")
class BillServiceTest {

    @Autowired
    private BillService businessInvoiceService;

    @Autowired
    private BillRepository businessInvoiceRepository;

    @Autowired
    private BillItemRepository businessInvoiceItemRepository;

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

        // bills.customer_id has an FK into customers(id) — every fixture below
        // needs a real row to point at.
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

    @Nested
    @DisplayName("create")
    class CreateInvoiceTests {

        @Test
        @DisplayName("create assigns a NumberSeries-generated invoice number")
        void create_assignsNumberSeriesInvoiceNumber() {
            CreateBillRequest.InvoiceLineItemRequest item = CreateBillRequest.InvoiceLineItemRequest.builder()
                .description("Test Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .build();

            CreateBillRequest request = CreateBillRequest.builder()
                .customerId(customerId)
                .items(java.util.List.of(item))
                .build();

            var response = businessInvoiceService.create(request);

            assertNotNull(response.getInvoiceNumber());
            assertTrue(response.getInvoiceNumber().startsWith("INV-"),
                "Expected NumberSeries-formatted number, got: " + response.getInvoiceNumber());
        }

        @Test
        @DisplayName("create issues sequential invoice numbers across calls")
        void create_multipleInvoices_sequentialNumbers() {
            CreateBillRequest.InvoiceLineItemRequest item = CreateBillRequest.InvoiceLineItemRequest.builder()
                .description("Test Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("50"))
                .build();
            CreateBillRequest request = CreateBillRequest.builder()
                .customerId(customerId)
                .items(java.util.List.of(item))
                .build();

            var first  = businessInvoiceService.create(request);
            var second = businessInvoiceService.create(request);

            assertNotEquals(first.getInvoiceNumber(), second.getInvoiceNumber());
        }
    }

    @Nested
    @DisplayName("addLineItem")
    class AddLineItemTests {

        @Test
        @DisplayName("addLineItem to draft invoice recalculates totals")
        void addLineItem_toDraftInvoice_recalculatesTotals() {
            Bill invoice = businessInvoiceRepository.save(Bill.builder()
                .invoiceNumber("BINV-001")
                .status(InvoiceStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .customerId(customerId)
                .build());

            AddBillItemRequest request = AddBillItemRequest.builder()
                .description("Test Item")
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("100"))
                .build();

            var response = businessInvoiceService.addLineItem(invoice.getId(), request);

            assertNotNull(response);
            assertNotNull(response.getTotalAmount());
            assertTrue(response.getTotalAmount().compareTo(BigDecimal.ZERO) > 0);
        }

        @Test
        @DisplayName("addLineItem to open invoice throws ResourceException")
        void addLineItem_toOpenInvoice_throwsResourceException() {
            Bill invoice = businessInvoiceRepository.save(Bill.builder()
                .invoiceNumber("BINV-002")
                .status(InvoiceStatus.OPEN)
                .subtotal(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .customerId(customerId)
                .build());

            AddBillItemRequest request = AddBillItemRequest.builder()
                .description("Test Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .build();

            assertThrows(com.mtbs.shared.exception.ResourceException.class, () ->
                businessInvoiceService.addLineItem(invoice.getId(), request)
            );
        }
    }

    @Nested
    @DisplayName("removeLineItem")
    class RemoveLineItemTests {

        @Test
        @DisplayName("removeLineItem recalculates total")
        void removeLineItem_recalculatesTotal() {
            Bill invoice = businessInvoiceRepository.save(Bill.builder()
                .invoiceNumber("BINV-003")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("200"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("200"))
                .customerId(customerId)
                .build());

            BillItem item = businessInvoiceItemRepository.save(BillItem.builder()
                .invoice(invoice)
                .description("Test Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("200"))
                .build());

            businessInvoiceService.removeLineItem(invoice.getId(), item.getId());

            Bill updated = businessInvoiceRepository.findById(invoice.getId()).orElseThrow();
            assertEquals(0, BigDecimal.ZERO.compareTo(updated.getTotalAmount()));
        }

        @Test
        @DisplayName("removeLineItem does not cause orphan removal exception")
        void removeLineItem_noOrphanRemovalException() {
            Bill invoice = businessInvoiceRepository.save(Bill.builder()
                .invoiceNumber("BINV-004")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(customerId)
                .build());

            BillItem item = businessInvoiceItemRepository.save(BillItem.builder()
                .invoice(invoice)
                .description("Test Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .build());

            assertDoesNotThrow(() ->
                businessInvoiceService.removeLineItem(invoice.getId(), item.getId())
            );
        }
    }

    @Nested
    @DisplayName("voidInvoice")
    class VoidInvoiceTests {

        @Test
        @DisplayName("voidInvoice open succeeds")
        void voidInvoice_open_succeeds() {
            Bill invoice = businessInvoiceRepository.save(Bill.builder()
                .invoiceNumber("BINV-008")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(customerId)
                .build());

            var response = businessInvoiceService.voidInvoice(invoice.getId());

            assertEquals(InvoiceStatus.VOID, response.getStatus());
        }

        @Test
        @DisplayName("voidInvoice paid invoice throws ResourceException")
        void voidInvoice_paidInvoice_throwsResourceException() {
            Bill invoice = businessInvoiceRepository.save(Bill.builder()
                .invoiceNumber("BINV-009")
                .status(InvoiceStatus.PAID)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(customerId)
                .build());

            assertThrows(com.mtbs.shared.exception.ResourceException.class, () ->
                businessInvoiceService.voidInvoice(invoice.getId())
            );
        }
    }

    @Nested
    @DisplayName("getById")
    class GetByIdTests {

        @Test
        @DisplayName("getById returns invoice with items")
        void getById_returnsInvoiceWithItems() {
            Bill invoice = businessInvoiceRepository.save(Bill.builder()
                .invoiceNumber("BINV-010")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(customerId)
                .build());

            businessInvoiceItemRepository.save(BillItem.builder()
                .invoice(invoice)
                .description("Test Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100"))
                .build());

            var response = businessInvoiceService.getById(invoice.getId());

            assertNotNull(response);
            assertEquals("BINV-010", response.getInvoiceNumber());
            assertEquals(1, response.getItems().size());
        }
    }

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("list returns paged results")
        void list_returnsPagedResults() {
            for (int i = 0; i < 3; i++) {
                businessInvoiceRepository.save(Bill.builder()
                    .invoiceNumber("BINV-LIST-" + i)
                    .status(InvoiceStatus.DRAFT)
                    .subtotal(BigDecimal.ZERO)
                    .taxAmount(BigDecimal.ZERO)
                    .totalAmount(BigDecimal.ZERO)
                    .customerId(customerId)
                    .build());
            }

            var page = businessInvoiceService.list(customerId, InvoiceStatus.DRAFT, org.springframework.data.domain.PageRequest.of(0, 10));

            assertNotNull(page);
            assertTrue(page.getTotalElements() >= 3);
        }
    }
}