package com.mtbs.business.invoice;

import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.customer.repository.CustomerRepository;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.business.invoice.repository.BillItemRepository;
import com.mtbs.business.invoice.repository.BillRepository;
import com.mtbs.business.invoice.service.BillPdfService;
import com.mtbs.business.invoice.template.BillRenderOptions;
import com.mtbs.business.invoice.template.CopyType;
import com.mtbs.shared.enums.bill.InvoiceStatus;
import com.mtbs.shared.enums.settings.PaperSize;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import com.mtbs.tenant.attachment.dto.AttachmentResponse;
import com.mtbs.tenant.attachment.enums.AttachmentPurpose;
import com.mtbs.tenant.attachment.service.AttachmentService;
import com.mtbs.tenant.settings.dto.UpdateShopSettingsRequest;
import com.mtbs.tenant.settings.service.ShopSettingsService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the full Phase 2.2 printing pipeline: BillPdfService resolving
 * a renderer via {billTemplate.code}:{paperSize}, each renderer actually
 * drawing content driven by ShopSettings, and the PDF being readable back.
 */
@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("BillPdfService / Printing Pipeline Integration Tests")
class BillPdfServiceTest {

    @Autowired
    private BillPdfService billPdfService;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private BillItemRepository billItemRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ShopSettingsService shopSettingsService;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    private String currentSchema;
    private Long billId;

    @BeforeEach
    void setUp() throws Exception {
        currentSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(currentSchema);

        Customer customer = customerRepository.save(Customer.builder()
                .name("Print Test Customer")
                .email("printtest@example.com")
                .phone("+91-9000000001")
                .address("99 Print Street")
                .build());

        Bill bill = billRepository.save(Bill.builder()
                .invoiceNumber("INV-TEST-0001")
                .status(InvoiceStatus.OPEN)
                .customerId(customer.getId())
                .currency("INR")
                .subtotal(new BigDecimal("1000.00"))
                .taxAmount(new BigDecimal("180.00"))
                .totalAmount(new BigDecimal("1180.00"))
                .build());
        billItemRepository.save(BillItem.builder()
                .invoice(bill)
                .description("Print Test Widget")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("1000.00"))
                .taxPercentage(new BigDecimal("18.00"))
                .taxAmount(new BigDecimal("180.00"))
                .total(new BigDecimal("1180.00"))
                .build());
        billId = bill.getId();

        // Small real PNG (1x1 pixel) so logo/signature embedding is genuinely exercised.
        byte[] pngBytes = onePixelPng();
        AttachmentResponse logo = attachmentService.upload(AttachmentPurpose.LOGO,
                new MockMultipartFile("file", "logo.png", "image/png", pngBytes));

        shopSettingsService.updateSettings(UpdateShopSettingsRequest.builder()
                .businessName("Print Test Shop")
                .address("1 Test Lane")
                .city("Testville")
                .gstin("27AAAPL1234C1ZV")
                .upiId("printtest@upi")
                .logoAttachmentId(logo.getId())
                .watermarkText("SAMPLE")
                .termsAndConditions("No returns after 7 days.")
                .footerMessage("Thanks for testing!")
                .build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(currentSchema);
    }

    @Nested
    @DisplayName("A4 (default paperSize)")
    class A4Tests {

        @Test
        @DisplayName("generatePdf produces a non-empty A4-sized PDF with shop content, watermark, and QR")
        void generatePdf_a4_containsExpectedContent() throws Exception {
            byte[] pdf = billPdfService.generatePdf(billId, BillRenderOptions.NONE);

            assertTrue(pdf.length > 0);
            try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
                Rectangle size = doc.getPage(1).getPageSize();
                assertEquals(595, Math.round(size.getWidth()), 2); // A4 width in points

                String text = PdfTextExtractor.getTextFromPage(doc.getPage(1));
                assertTrue(text.contains("Print Test Shop"), "Expected business name in PDF text");
                assertTrue(text.contains("GSTIN: 27AAAPL1234C1ZV"), "Expected GST line in PDF text");
                assertTrue(text.contains("INV-TEST-0001"), "Expected invoice number in PDF text");
                assertTrue(text.contains("No returns after 7 days."), "Expected terms in PDF text");
                assertTrue(text.contains("Thanks for testing!"), "Expected footer message in PDF text");
                assertTrue(text.contains("Amount in words"), "Expected amount-in-words line");
            }
        }

        @Test
        @DisplayName("generatePdf with copyType prints the copy label")
        void generatePdf_withCopyType_printsLabel() throws Exception {
            byte[] pdf = billPdfService.generatePdf(billId, new BillRenderOptions(CopyType.DUPLICATE));

            try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
                String text = PdfTextExtractor.getTextFromPage(doc.getPage(1));
                assertTrue(text.contains("DUPLICATE FOR SUPPLIER"), "Expected copy label in PDF text");
            }
        }
    }

    @Nested
    @DisplayName("Dangling attachment reference")
    class DanglingAttachmentTests {

        @Test
        @DisplayName("a logoAttachmentId left pointing at a since-deleted attachment still produces a PDF, "
                + "not an UnexpectedRollbackException")
        void generatePdf_danglingLogoAttachment_stillSucceeds() throws Exception {
            // Reproduces the exact production bug. updateSettings() validates the
            // attachment exists at set-time (ShopSettingsService.applyLogo — "throws
            // if not found"), so a dangling reference can only arise the way it
            // really did: the logo was valid when set, then deleted afterward —
            // AttachmentService.delete() doesn't clear ShopSettings' back-reference.
            //
            // AttachmentService.getFileBytes() throwing for that now-stale reference
            // used to mark the *shared* ambient read-only transaction rollback-only
            // even though BillRenderSupport.loadImage() swallows the exception and
            // PDF generation appears to succeed — generatePdf() would then fail to
            // commit with an UnexpectedRollbackException on return, surfacing as a
            // bare 500 with no trace of the real cause. getFileBytes() now runs in
            // its own REQUIRES_NEW transaction specifically so this can't happen.
            Long danglingLogoId = shopSettingsService.getSettings().getLogoAttachmentId();
            attachmentService.delete(danglingLogoId);

            byte[] pdf = assertDoesNotThrow(() -> billPdfService.generatePdf(billId, BillRenderOptions.NONE));

            assertTrue(pdf.length > 0);
            try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
                String text = PdfTextExtractor.getTextFromPage(doc.getPage(1));
                assertTrue(text.contains("Print Test Shop"), "PDF should still render fully, just without the logo");
            }
        }
    }

    @Nested
    @DisplayName("Thermal paper sizes")
    class ThermalTests {

        @ParameterizedTest(name = "{0} produces correctly-sized PDF with shop content")
        @EnumSource(value = PaperSize.class, names = {"THERMAL_58MM", "THERMAL_80MM"})
        void generatePdf_thermal_correctWidthAndContent(PaperSize paperSize) throws Exception {
            shopSettingsService.updateSettings(UpdateShopSettingsRequest.builder()
                    .paperSize(paperSize)
                    .build());

            byte[] pdf = billPdfService.generatePdf(billId, BillRenderOptions.NONE);
            assertTrue(pdf.length > 0);

            try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
                Rectangle size = doc.getPage(1).getPageSize();
                float expectedWidthPt = (paperSize == PaperSize.THERMAL_58MM ? 58f : 80f) * 72f / 25.4f;
                assertEquals(expectedWidthPt, size.getWidth(), 1.0f);

                String text = PdfTextExtractor.getTextFromPage(doc.getPage(1));
                assertTrue(text.contains("Print Test Shop"), "Expected business name in thermal PDF text");
                assertTrue(text.contains("INV-TEST-0001"), "Expected invoice number in thermal PDF text");
                assertTrue(text.contains("1180.00"), "Expected total amount in thermal PDF text");
            }
        }
    }

    private byte[] onePixelPng() {
        // Minimal valid 1x1 transparent PNG.
        return new byte[] {
            (byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,
            0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x06,0x00,0x00,0x00,0x1F,0x15,(byte)0xC4,
            (byte)0x89,0x00,0x00,0x00,0x0A,0x49,0x44,0x41,0x54,0x78,(byte)0x9C,0x63,0x00,0x01,0x00,
            0x00,0x05,0x00,0x01,0x0D,0x0A,0x2D,(byte)0xB4,0x00,0x00,0x00,0x00,0x49,0x45,0x4E,0x44,
            (byte)0xAE,0x42,0x60,(byte)0x82
        };
    }
}
