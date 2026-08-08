package com.mtbs.business.invoice.service;

import com.itextpdf.kernel.colors.Color;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.service.SubscriptionService;
import com.mtbs.business.customer.service.CustomerService;
import com.mtbs.business.invoice.entity.BusinessInvoice;
import com.mtbs.business.invoice.entity.BusinessInvoiceItem;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.invoice.repository.BusinessInvoiceItemRepository;
import com.mtbs.business.invoice.repository.BusinessInvoiceRepository;
import com.mtbs.billing.service.UsageService;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessInvoicePdfService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneOffset.UTC);

    private static final DeviceRgb HEADER_BG   = new DeviceRgb(37, 99, 235);
    private static final DeviceRgb TABLE_HEADER = new DeviceRgb(248, 250, 252);
    private static final DeviceRgb BORDER_COLOR = new DeviceRgb(226, 232, 240);
    private static final DeviceRgb MUTED_TEXT   = new DeviceRgb(100, 116, 139);

    private final BusinessInvoiceRepository invoiceRepository;
    private final BusinessInvoiceItemRepository itemRepository;
    private final CustomerService customerService;
    private final SubscriptionService subscriptionService;
    private final UsageService usageService;

    @Transactional(readOnly = true)
    public byte[] generatePdf(Long invoiceId) {
        BusinessInvoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("BusinessInvoice", invoiceId));

        List<BusinessInvoiceItem> items = itemRepository.findAllByInvoiceId(invoiceId);
        Customer customer = customerService.getEntityById(invoice.getCustomerId());

        log.info("Generating PDF for businessInvoice={}", invoice.getInvoiceNumber());

        byte[] pdf;
        try {
            pdf = buildPdf(invoice, items, customer);
            log.info("PDF generated — invoiceNumber={}, bytes={}", invoice.getInvoiceNumber(), pdf.length);
        } catch (Exception e) {
            log.error("PDF generation failed for invoiceId={}: {}", invoiceId, e.getMessage(), e);
            throw ResourceException.invalid("PDF generation failed: " + e.getMessage());
        }

        recordStorageUsageAsync(pdf.length);

        return pdf;
    }

    @Async
    public void recordStorageUsageAsync(long fileSizeBytes) {
        try {
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                log.warn("No tenant context — skipping storage recording for business invoice");
                return;
            }

            Optional<Subscription> subOpt = subscriptionService.findFirstSubscriptionByStatuses(
                    List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING));

            if (subOpt.isEmpty()) {
                log.warn("No active subscription for tenantId={} — skipping storage recording", tenantId);
                return;
            }

            Subscription subscription = subOpt.get();

            usageService.recordStorageUsage(
                    tenantId,
                    subscription.getId(),
                    fileSizeBytes,
                    subscription.getCurrentPeriodStart(),
                    subscription.getCurrentPeriodEnd()
            );
            log.debug("Storage usage recorded for business invoice, tenantId={}, bytes={}", tenantId, fileSizeBytes);
        } catch (Exception e) {
            log.error("Failed to record storage usage for business invoice: {}", e.getMessage(), e);
        }
    }

    private byte[] buildPdf(BusinessInvoice invoice,
                           List<BusinessInvoiceItem> items,
                           Customer customer) throws Exception {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer  = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document  = new Document(pdfDoc, PageSize.A4);
        document.setMargins(40, 50, 40, 50);

        PdfFont regular = PdfFontFactory.createFont("Helvetica");
        PdfFont bold    = PdfFontFactory.createFont("Helvetica-Bold");

        addHeader(document, invoice, bold, regular);
        addBillingSection(document, invoice, customer, bold, regular);
        addItemsTable(document, items, bold, regular);
        addTotals(document, invoice, bold, regular);

        if (invoice.getNotes() != null && !invoice.getNotes().isBlank()) {
            addNotes(document, invoice.getNotes(), bold, regular);
        }

        addFooter(document, invoice, regular);

        document.close();
        return baos.toByteArray();
    }

    private void addHeader(Document document, BusinessInvoice invoice,
                           PdfFont bold, PdfFont regular) {
        Table headerTable = new Table(UnitValue.createPercentArray(new float[]{60, 40}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(20);

        Cell titleCell = new Cell()
                .add(new Paragraph("TAX INVOICE")
                        .setFont(bold).setFontSize(22)
                        .setFontColor(ColorConstants.WHITE))
                .add(new Paragraph(invoice.getInvoiceNumber())
                        .setFont(regular).setFontSize(11)
                        .setFontColor(new DeviceRgb(186, 230, 253)))
                .setBackgroundColor(HEADER_BG)
                .setBorder(Border.NO_BORDER)
                .setPadding(16);
        headerTable.addCell(titleCell);

        String invoiceDate = formatInstant(invoice.getCreatedAt());
        String dueDate     = invoice.getDueDate() != null
                ? formatInstant(invoice.getDueDate()) : "—";

        Cell metaCell = new Cell()
                .add(new Paragraph("Invoice date")
                        .setFont(regular).setFontSize(9)
                        .setFontColor(MUTED_TEXT))
                .add(new Paragraph(invoiceDate)
                        .setFont(bold).setFontSize(11)
                        .setMarginBottom(8))
                .add(new Paragraph("Due date")
                        .setFont(regular).setFontSize(9)
                        .setFontColor(MUTED_TEXT))
                .add(new Paragraph(dueDate)
                        .setFont(bold).setFontSize(11))
                .setBackgroundColor(HEADER_BG)
                .setBorder(Border.NO_BORDER)
                .setPadding(16)
                .setTextAlignment(TextAlignment.RIGHT);
        headerTable.addCell(metaCell);

        document.add(headerTable);
    }

    private void addBillingSection(Document document, BusinessInvoice invoice,
                                   Customer customer, PdfFont bold, PdfFont regular) {
        Table billingTable = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(20);

        StringBuilder billTo = new StringBuilder();
        billTo.append(customer.getName()).append("\n");
        if (customer.getEmail()   != null) billTo.append(customer.getEmail()).append("\n");
        if (customer.getPhone()   != null) billTo.append(customer.getPhone()).append("\n");
        if (customer.getAddress() != null) billTo.append(customer.getAddress()).append("\n");
        if (customer.getGstin()   != null) billTo.append("GSTIN: ").append(customer.getGstin());

        Cell billToCell = new Cell()
                .add(new Paragraph("BILL TO").setFont(bold).setFontSize(9)
                        .setFontColor(MUTED_TEXT).setMarginBottom(4))
                .add(new Paragraph(billTo.toString())
                        .setFont(regular).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setPaddingRight(12);
        billingTable.addCell(billToCell);

        Cell statusCell = new Cell()
                .add(new Paragraph("STATUS").setFont(bold).setFontSize(9)
                        .setFontColor(MUTED_TEXT).setMarginBottom(4))
                .add(new Paragraph(invoice.getStatus().name())
                        .setFont(bold).setFontSize(12))
                .add(new Paragraph(invoice.getCurrency() + " " + invoice.getTotalAmount().toPlainString())
                        .setFont(bold).setFontSize(16)
                        .setFontColor(HEADER_BG)
                        .setMarginTop(8))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT);
        billingTable.addCell(statusCell);

        document.add(billingTable);
        document.add(new com.itextpdf.layout.element.LineSeparator(
                new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(0.5f))
                .setMarginBottom(16));
    }

    private void addItemsTable(Document document, List<BusinessInvoiceItem> items,
                               PdfFont bold, PdfFont regular) {
        Table table = new Table(UnitValue.createPercentArray(new float[]{38, 8, 14, 8, 14, 18}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(16);

        String[] headers = {"Description", "Qty", "Unit price", "Tax %", "Tax", "Total"};
        for (String h : headers) {
            table.addHeaderCell(new Cell()
                    .add(new Paragraph(h).setFont(bold).setFontSize(9))
                    .setBackgroundColor(TABLE_HEADER)
                    .setBorderBottom(new SolidBorder(BORDER_COLOR, 1))
                    .setBorderTop(Border.NO_BORDER)
                    .setBorderLeft(Border.NO_BORDER)
                    .setBorderRight(Border.NO_BORDER)
                    .setPadding(7));
        }

        for (BusinessInvoiceItem item : items) {
            table.addCell(itemCell(item.getDescription(), regular, TextAlignment.LEFT));
            table.addCell(itemCell(item.getQuantity().toPlainString(), regular, TextAlignment.CENTER));
            table.addCell(itemCell(fmt(item.getUnitPrice()), regular, TextAlignment.RIGHT));
            table.addCell(itemCell(item.getTaxPercentage().toPlainString() + "%", regular, TextAlignment.CENTER));
            table.addCell(itemCell(fmt(item.getTaxAmount()), regular, TextAlignment.RIGHT));
            table.addCell(itemCell(fmt(item.getTotal()), regular, TextAlignment.RIGHT));
        }

        document.add(table);
    }

    private void addTotals(Document document, BusinessInvoice invoice,
                           PdfFont bold, PdfFont regular) {
        Table totalsTable = new Table(UnitValue.createPercentArray(new float[]{60, 40}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(20);

        totalsTable.addCell(new Cell().setBorder(Border.NO_BORDER));

        Table innerTotals = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100));

        addTotalRow(innerTotals, "Subtotal", fmt(invoice.getSubtotal()), regular, false);
        addTotalRow(innerTotals, "Tax",      fmt(invoice.getTaxAmount()), regular, false);

        innerTotals.addCell(new Cell(1, 2)
                .setBorderTop(new SolidBorder(BORDER_COLOR, 0.5f))
                .setBorderBottom(Border.NO_BORDER)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .setHeight(4));

        addTotalRow(innerTotals,
                "Total (" + invoice.getCurrency() + ")",
                fmt(invoice.getTotalAmount()),
                bold, true);

        totalsTable.addCell(new Cell().add(innerTotals).setBorder(Border.NO_BORDER));
        document.add(totalsTable);
    }

    private void addNotes(Document document, String notes, PdfFont bold, PdfFont regular) {
        document.add(new Paragraph("Notes")
                .setFont(bold).setFontSize(10).setFontColor(MUTED_TEXT).setMarginBottom(4));
        document.add(new Paragraph(notes)
                .setFont(regular).setFontSize(10)
                .setBorderLeft(new SolidBorder(BORDER_COLOR, 3))
                .setPaddingLeft(10)
                .setMarginBottom(20));
    }

    private void addFooter(Document document, BusinessInvoice invoice, PdfFont regular) {
        document.add(new com.itextpdf.layout.element.LineSeparator(
                new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(0.5f))
                .setMarginBottom(10));

        String dueText = invoice.getDueDate() != null
                ? "Payment due by " + formatInstant(invoice.getDueDate()) + ".  "
                : "";

        document.add(new Paragraph(dueText + "Thank you for your business.")
                .setFont(regular).setFontSize(9)
                .setFontColor(MUTED_TEXT)
                .setTextAlignment(TextAlignment.CENTER));
    }

    private Cell itemCell(String text, PdfFont font, TextAlignment align) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(9))
                .setBorderTop(Border.NO_BORDER)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(BORDER_COLOR, 0.5f))
                .setPadding(6)
                .setTextAlignment(align);
    }

    private void addTotalRow(Table table, String label, String value,
                             PdfFont font, boolean highlight) {
        Color textColor = highlight ? HEADER_BG : ColorConstants.BLACK;
        float fontSize = highlight ? 12f : 10f;

        table.addCell(new Cell()
                .add(new Paragraph(label).setFont(font).setFontSize(fontSize).setFontColor(textColor))
                .setBorder(Border.NO_BORDER).setPadding(4));
        table.addCell(new Cell()
                .add(new Paragraph(value).setFont(font).setFontSize(fontSize).setFontColor(textColor))
                .setBorder(Border.NO_BORDER).setPadding(4)
                .setTextAlignment(TextAlignment.RIGHT));
    }

    private String fmt(BigDecimal value) {
        return value != null ? value.toPlainString() : "0.00";
    }

    private String formatInstant(Instant instant) {
        return instant != null ? DATE_FMT.format(instant) : "—";
    }
}
