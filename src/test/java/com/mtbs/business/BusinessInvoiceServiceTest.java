package com.mtbs.business;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.business.invoice.dto.AddLineItemRequest;
import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.business.invoice.entity.BusinessInvoiceItem;
import com.mtbs.business.invoice.repository.BusinessInvoiceItemRepository;
import com.mtbs.business.invoice.repository.BusinessInvoiceRepository;
import com.mtbs.business.invoice.service.BusinessInvoiceService;
import com.mtbs.shared.enums.billing.InvoiceStatus;
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
@DisplayName("BusinessInvoiceService Integration Tests")
class BusinessInvoiceServiceTest {

    @Autowired
    private BusinessInvoiceService businessInvoiceService;

    @Autowired
    private BusinessInvoiceRepository businessInvoiceRepository;

    @Autowired
    private BusinessInvoiceItemRepository businessInvoiceItemRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

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
    @DisplayName("addLineItem")
    class AddLineItemTests {

        @Test
        @DisplayName("addLineItem to draft invoice recalculates totals")
        void addLineItem_toDraftInvoice_recalculatesTotals() {
            BusinessInvoice invoice = businessInvoiceRepository.save(BusinessInvoice.builder()
                .invoiceNumber("BINV-001")
                .status(InvoiceStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .customerId(1L)
                .build());

            AddLineItemRequest request = AddLineItemRequest.builder()
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
            BusinessInvoice invoice = businessInvoiceRepository.save(BusinessInvoice.builder()
                .invoiceNumber("BINV-002")
                .status(InvoiceStatus.OPEN)
                .subtotal(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .customerId(1L)
                .build());

            AddLineItemRequest request = AddLineItemRequest.builder()
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
            BusinessInvoice invoice = businessInvoiceRepository.save(BusinessInvoice.builder()
                .invoiceNumber("BINV-003")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("200"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("200"))
                .customerId(1L)
                .build());

            BusinessInvoiceItem item = businessInvoiceItemRepository.save(BusinessInvoiceItem.builder()
                .invoice(invoice)
                .description("Test Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("200"))
                .build());

            businessInvoiceService.removeLineItem(invoice.getId(), item.getId());

            BusinessInvoice updated = businessInvoiceRepository.findById(invoice.getId()).orElseThrow();
            assertEquals(BigDecimal.ZERO, updated.getTotalAmount());
        }

        @Test
        @DisplayName("removeLineItem does not cause orphan removal exception")
        void removeLineItem_noOrphanRemovalException() {
            BusinessInvoice invoice = businessInvoiceRepository.save(BusinessInvoice.builder()
                .invoiceNumber("BINV-004")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(1L)
                .build());

            BusinessInvoiceItem item = businessInvoiceItemRepository.save(BusinessInvoiceItem.builder()
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
            BusinessInvoice invoice = businessInvoiceRepository.save(BusinessInvoice.builder()
                .invoiceNumber("BINV-008")
                .status(InvoiceStatus.OPEN)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(1L)
                .build());

            var response = businessInvoiceService.voidInvoice(invoice.getId());

            assertEquals(InvoiceStatus.VOID, response.getStatus());
        }

        @Test
        @DisplayName("voidInvoice paid invoice throws ResourceException")
        void voidInvoice_paidInvoice_throwsResourceException() {
            BusinessInvoice invoice = businessInvoiceRepository.save(BusinessInvoice.builder()
                .invoiceNumber("BINV-009")
                .status(InvoiceStatus.PAID)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(1L)
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
            BusinessInvoice invoice = businessInvoiceRepository.save(BusinessInvoice.builder()
                .invoiceNumber("BINV-010")
                .status(InvoiceStatus.DRAFT)
                .subtotal(new BigDecimal("100"))
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100"))
                .customerId(1L)
                .build());

            businessInvoiceItemRepository.save(BusinessInvoiceItem.builder()
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
                businessInvoiceRepository.save(BusinessInvoice.builder()
                    .invoiceNumber("BINV-LIST-" + i)
                    .status(InvoiceStatus.DRAFT)
                    .subtotal(BigDecimal.ZERO)
                    .taxAmount(BigDecimal.ZERO)
                    .totalAmount(BigDecimal.ZERO)
                    .customerId(1L)
                    .build());
            }

            var page = businessInvoiceService.list(1L, InvoiceStatus.DRAFT, org.springframework.data.domain.PageRequest.of(0, 10));

            assertNotNull(page);
            assertTrue(page.getTotalElements() >= 3);
        }
    }
}