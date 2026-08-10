package com.mtbs.business.invoice.template;

import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.CompressionConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.LineSeparator;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.settings.entity.ShopSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A4 paper renderer for the "Simple Cash Memo" style — full-page tax-invoice
 * layout with a colored header bar, items table, and totals block. Registry
 * key is built by BillPdfService as "{billTemplate.code}:A4".
 *
 * This is Phase 2.1's CashMemoV1Renderer, renamed and extended with logo/
 * QR/watermark/copy-label/signature-image rendering and ShopSettings-driven
 * margin/font-size.
 */
@Component
@RequiredArgsConstructor
public class A4Renderer implements BillTemplateRenderer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneOffset.UTC);

    private static final DeviceRgb HEADER_BG    = new DeviceRgb(37, 99, 235);
    private static final DeviceRgb TABLE_HEADER = new DeviceRgb(248, 250, 252);
    private static final DeviceRgb BORDER_COLOR = new DeviceRgb(226, 232, 240);
    private static final DeviceRgb MUTED_TEXT   = new DeviceRgb(100, 116, 139);

    private final BillRenderSupport support;

    @Override
    public String code() {
        return "CASH_MEMO_V1:A4";
    }

    @Override
    public byte[] render(Bill invoice, List<BillItem> items, Customer customer, ShopSettings settings, BillRenderOptions options) {
        try {
            return buildPdf(invoice, items, customer, settings, options);
        } catch (Exception e) {
            throw ResourceException.invalid("PDF generation failed: " + e.getMessage());
        }
    }

    private byte[] buildPdf(Bill invoice, List<BillItem> items, Customer customer,
                             ShopSettings settings, BillRenderOptions options) throws Exception {
        float baseFontSize = settings.getFontSize() != null ? settings.getFontSize() : 10;
        float marginPt = mmToPt(settings.getMargin() != null ? settings.getMargin() : 5);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WriterProperties writerProperties = new WriterProperties()
                .setCompressionLevel(CompressionConstants.BEST_COMPRESSION)
                .setFullCompressionMode(true);
        PdfWriter writer   = new PdfWriter(baos, writerProperties);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document  = new Document(pdfDoc, PageSize.A4);
        document.setMargins(marginPt + 35, marginPt + 45, marginPt + 35, marginPt + 45);

        PdfFont regular = PdfFontFactory.createFont("Helvetica");
        PdfFont bold    = PdfFontFactory.createFont("Helvetica-Bold");

        addBusinessHeader(document, settings, bold, regular, baseFontSize);
        addHeader(document, invoice, options, bold, regular, baseFontSize);
        addBillingSection(document, invoice, customer, settings, bold, regular, baseFontSize);
        addItemsTable(document, items, bold, regular, baseFontSize);
        addTotals(document, invoice, settings, bold, regular, baseFontSize);

        if (invoice.getNotes() != null && !invoice.getNotes().isBlank()) {
            addNotes(document, invoice.getNotes(), bold, regular, baseFontSize);
        }

        addQrCode(document, invoice, settings, pdfDoc, regular, baseFontSize);
        addSignature(document, settings, regular, baseFontSize);
        addFooter(document, invoice, settings, regular, bold, baseFontSize);

        support.drawWatermark(pdfDoc, settings);

        document.close();
        return baos.toByteArray();
    }

    private void addBusinessHeader(Document document, ShopSettings settings, PdfFont bold, PdfFont regular, float baseFontSize) {
        Image logo = support.loadLogo(settings);
        if (logo != null) {
            logo.setMaxWidth(120).setMaxHeight(60);
            document.add(logo);
        }

        if (settings.getBusinessName() == null) {
            return;
        }

        document.add(new Paragraph(settings.getBusinessName())
                .setFont(bold).setFontSize(baseFontSize * 1.6f).setMarginBottom(2));

        StringBuilder details = new StringBuilder();
        if (settings.getAddress() != null) details.append(settings.getAddress());
        if (settings.getCity() != null || settings.getState() != null || settings.getPincode() != null) {
            if (!details.isEmpty()) details.append(", ");
            details.append(String.join(" ",
                    nullToEmpty(settings.getCity()), nullToEmpty(settings.getState()), nullToEmpty(settings.getPincode())).trim());
        }
        if (!details.isEmpty()) {
            document.add(new Paragraph(details.toString())
                    .setFont(regular).setFontSize(baseFontSize * 0.9f).setFontColor(MUTED_TEXT).setMarginBottom(1));
        }

        StringBuilder contact = new StringBuilder();
        if (settings.getMobile() != null) contact.append(settings.getMobile());
        if (settings.getEmail() != null) {
            if (!contact.isEmpty()) contact.append("  |  ");
            contact.append(settings.getEmail());
        }
        if (Boolean.TRUE.equals(settings.getShowGst()) && settings.getGstin() != null) {
            if (!contact.isEmpty()) contact.append("  |  ");
            contact.append("GSTIN: ").append(settings.getGstin());
        }
        if (!contact.isEmpty()) {
            document.add(new Paragraph(contact.toString())
                    .setFont(regular).setFontSize(baseFontSize * 0.9f).setFontColor(MUTED_TEXT).setMarginBottom(12));
        }
    }

    private void addHeader(Document document, Bill invoice, BillRenderOptions options, PdfFont bold, PdfFont regular, float baseFontSize) {
        Table headerTable = new Table(UnitValue.createPercentArray(new float[]{60, 40}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(20);

        String copyLabel = support.copyLabelText(options);
        Cell titleCell = new Cell()
                .add(new Paragraph("TAX INVOICE")
                        .setFont(bold).setFontSize(baseFontSize * 2.2f)
                        .setFontColor(ColorConstants.WHITE))
                .add(new Paragraph(invoice.getInvoiceNumber())
                        .setFont(regular).setFontSize(baseFontSize * 1.1f)
                        .setFontColor(new DeviceRgb(186, 230, 253)))
                .setBackgroundColor(HEADER_BG)
                .setBorder(Border.NO_BORDER)
                .setPadding(16);
        if (copyLabel != null) {
            titleCell.add(new Paragraph(copyLabel)
                    .setFont(bold).setFontSize(baseFontSize * 0.8f)
                    .setFontColor(ColorConstants.WHITE).setMarginTop(4));
        }
        headerTable.addCell(titleCell);

        String invoiceDate = formatInstant(invoice.getCreatedAt());
        String dueDate     = invoice.getDueDate() != null
                ? formatInstant(invoice.getDueDate()) : "—";

        Cell metaCell = new Cell()
                .add(new Paragraph("Invoice date")
                        .setFont(regular).setFontSize(baseFontSize * 0.9f)
                        .setFontColor(MUTED_TEXT))
                .add(new Paragraph(invoiceDate)
                        .setFont(bold).setFontSize(baseFontSize * 1.1f)
                        .setMarginBottom(8))
                .add(new Paragraph("Due date")
                        .setFont(regular).setFontSize(baseFontSize * 0.9f)
                        .setFontColor(MUTED_TEXT))
                .add(new Paragraph(dueDate)
                        .setFont(bold).setFontSize(baseFontSize * 1.1f))
                .setBackgroundColor(HEADER_BG)
                .setBorder(Border.NO_BORDER)
                .setPadding(16)
                .setTextAlignment(TextAlignment.RIGHT);
        headerTable.addCell(metaCell);

        document.add(headerTable);
    }

    private void addBillingSection(Document document, Bill invoice, Customer customer,
                                    ShopSettings settings, PdfFont bold, PdfFont regular, float baseFontSize) {
        Table billingTable = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(20);

        StringBuilder billTo = new StringBuilder();
        billTo.append(customer.getName()).append("\n");
        if (customer.getEmail() != null) billTo.append(customer.getEmail()).append("\n");
        if (customer.getPhone() != null) billTo.append(customer.getPhone()).append("\n");
        if (Boolean.TRUE.equals(settings.getShowCustomerAddress()) && customer.getAddress() != null) {
            billTo.append(customer.getAddress()).append("\n");
        }
        if (customer.getGstin() != null) billTo.append("GSTIN: ").append(customer.getGstin());

        Cell billToCell = new Cell()
                .add(new Paragraph("BILL TO").setFont(bold).setFontSize(baseFontSize * 0.9f)
                        .setFontColor(MUTED_TEXT).setMarginBottom(4))
                .add(new Paragraph(billTo.toString())
                        .setFont(regular).setFontSize(baseFontSize))
                .setBorder(Border.NO_BORDER)
                .setPaddingRight(12);
        billingTable.addCell(billToCell);

        Cell statusCell = new Cell()
                .add(new Paragraph("STATUS").setFont(bold).setFontSize(baseFontSize * 0.9f)
                        .setFontColor(MUTED_TEXT).setMarginBottom(4))
                .add(new Paragraph(invoice.getStatus().name())
                        .setFont(bold).setFontSize(baseFontSize * 1.2f))
                .add(new Paragraph(invoice.getCurrency() + " " + invoice.getTotalAmount().toPlainString())
                        .setFont(bold).setFontSize(baseFontSize * 1.6f)
                        .setFontColor(HEADER_BG)
                        .setMarginTop(8))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.RIGHT);
        billingTable.addCell(statusCell);

        document.add(billingTable);
        document.add(new LineSeparator(new SolidLine(0.5f)).setMarginBottom(16));
    }

    private void addItemsTable(Document document, List<BillItem> items, PdfFont bold, PdfFont regular, float baseFontSize) {
        Table table = new Table(UnitValue.createPercentArray(new float[]{38, 8, 14, 8, 14, 18}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(16);

        String[] headers = {"Description", "Qty", "Unit price", "Tax %", "Tax", "Total"};
        for (String h : headers) {
            table.addHeaderCell(new Cell()
                    .add(new Paragraph(h).setFont(bold).setFontSize(baseFontSize * 0.9f))
                    .setBackgroundColor(TABLE_HEADER)
                    .setBorderBottom(new SolidBorder(BORDER_COLOR, 1))
                    .setBorderTop(Border.NO_BORDER)
                    .setBorderLeft(Border.NO_BORDER)
                    .setBorderRight(Border.NO_BORDER)
                    .setPadding(7));
        }

        for (BillItem item : items) {
            table.addCell(itemCell(item.getDescription(), regular, TextAlignment.LEFT, baseFontSize));
            table.addCell(itemCell(item.getQuantity().toPlainString(), regular, TextAlignment.CENTER, baseFontSize));
            table.addCell(itemCell(fmt(item.getUnitPrice()), regular, TextAlignment.RIGHT, baseFontSize));
            table.addCell(itemCell(item.getTaxPercentage().toPlainString() + "%", regular, TextAlignment.CENTER, baseFontSize));
            table.addCell(itemCell(fmt(item.getTaxAmount()), regular, TextAlignment.RIGHT, baseFontSize));
            table.addCell(itemCell(fmt(item.getTotal()), regular, TextAlignment.RIGHT, baseFontSize));
        }

        document.add(table);
    }

    private void addTotals(Document document, Bill invoice, ShopSettings settings, PdfFont bold, PdfFont regular, float baseFontSize) {
        Table totalsTable = new Table(UnitValue.createPercentArray(new float[]{60, 40}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(20);

        totalsTable.addCell(new Cell().setBorder(Border.NO_BORDER));

        Table innerTotals = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100));

        addTotalRow(innerTotals, "Subtotal", fmt(invoice.getSubtotal()), regular, false, baseFontSize);
        addTotalRow(innerTotals, "Tax",      fmt(invoice.getTaxAmount()), regular, false, baseFontSize);

        innerTotals.addCell(new Cell(1, 2)
                .setBorderTop(new SolidBorder(BORDER_COLOR, 0.5f))
                .setBorderBottom(Border.NO_BORDER)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .setHeight(4));

        addTotalRow(innerTotals,
                "Total (" + invoice.getCurrency() + ")",
                fmt(invoice.getTotalAmount()),
                bold, true, baseFontSize);

        totalsTable.addCell(new Cell().add(innerTotals).setBorder(Border.NO_BORDER));
        document.add(totalsTable);

        if (Boolean.TRUE.equals(settings.getShowAmountInWords())) {
            String words = support.amountInWords(invoice.getTotalAmount(), invoice.getCurrency());
            document.add(new Paragraph("Amount in words: " + words)
                    .setFont(regular).setFontSize(baseFontSize * 0.9f).setFontColor(MUTED_TEXT).setMarginBottom(16));
        }
    }

    private void addQrCode(Document document, Bill invoice, ShopSettings settings, PdfDocument pdfDoc, PdfFont regular, float baseFontSize) {
        Image qr = support.generateUpiQrCode(invoice, settings, pdfDoc);
        if (qr == null) {
            return;
        }
        qr.setMaxWidth(70).setMaxHeight(70);
        Table qrTable = new Table(UnitValue.createPercentArray(new float[]{85, 15}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(10);
        qrTable.addCell(new Cell().setBorder(Border.NO_BORDER));
        Cell qrCell = new Cell()
                .add(qr)
                .add(new Paragraph("Scan to pay").setFont(regular).setFontSize(baseFontSize * 0.75f)
                        .setFontColor(MUTED_TEXT).setTextAlignment(TextAlignment.CENTER))
                .setBorder(Border.NO_BORDER)
                .setTextAlignment(TextAlignment.CENTER);
        qrTable.addCell(qrCell);
        document.add(qrTable);
    }

    private void addNotes(Document document, String notes, PdfFont bold, PdfFont regular, float baseFontSize) {
        document.add(new Paragraph("Notes")
                .setFont(bold).setFontSize(baseFontSize).setFontColor(MUTED_TEXT).setMarginBottom(4));
        document.add(new Paragraph(notes)
                .setFont(regular).setFontSize(baseFontSize)
                .setBorderLeft(new SolidBorder(BORDER_COLOR, 3))
                .setPaddingLeft(10)
                .setMarginBottom(20));
    }

    private void addSignature(Document document, ShopSettings settings, PdfFont regular, float baseFontSize) {
        if (!Boolean.TRUE.equals(settings.getShowSignature())) {
            return;
        }

        Image signatureImage = support.loadSignatureImage(settings);
        if (signatureImage != null) {
            signatureImage.setMaxWidth(120).setMaxHeight(50);
            signatureImage.setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.RIGHT);
            document.add(signatureImage);
        } else {
            document.add(new Paragraph("\n\n_______________________")
                    .setFont(regular).setFontSize(baseFontSize)
                    .setTextAlignment(TextAlignment.RIGHT).setMarginTop(20));
        }
        document.add(new Paragraph("Authorized Signatory")
                .setFont(regular).setFontSize(baseFontSize * 0.9f).setFontColor(MUTED_TEXT)
                .setTextAlignment(TextAlignment.RIGHT));
    }

    private void addFooter(Document document, Bill invoice, ShopSettings settings, PdfFont regular, PdfFont bold, float baseFontSize) {
        document.add(new LineSeparator(new SolidLine(0.5f)).setMarginTop(16).setMarginBottom(10));

        if (settings.getTermsAndConditions() != null && !settings.getTermsAndConditions().isBlank()) {
            document.add(new Paragraph("Terms & Conditions").setFont(bold).setFontSize(baseFontSize * 0.9f).setFontColor(MUTED_TEXT));
            document.add(new Paragraph(settings.getTermsAndConditions())
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT).setMarginBottom(6));
        }

        if (settings.getWarrantyText() != null && !settings.getWarrantyText().isBlank()) {
            document.add(new Paragraph(settings.getWarrantyText())
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT).setMarginBottom(6));
        }

        String dueText = invoice.getDueDate() != null
                ? "Payment due by " + formatInstant(invoice.getDueDate()) + ".  "
                : "";
        String closing = settings.getFooterMessage() != null && !settings.getFooterMessage().isBlank()
                ? settings.getFooterMessage()
                : "Thank you for your business.";

        document.add(new Paragraph(dueText + closing)
                .setFont(regular).setFontSize(baseFontSize * 0.9f)
                .setFontColor(MUTED_TEXT)
                .setTextAlignment(TextAlignment.CENTER));
    }

    private Cell itemCell(String text, PdfFont font, TextAlignment align, float baseFontSize) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(baseFontSize * 0.9f))
                .setBorderTop(Border.NO_BORDER)
                .setBorderLeft(Border.NO_BORDER)
                .setBorderRight(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(BORDER_COLOR, 0.5f))
                .setPadding(6)
                .setTextAlignment(align);
    }

    private void addTotalRow(Table table, String label, String value, PdfFont font, boolean highlight, float baseFontSize) {
        Color textColor = highlight ? HEADER_BG : ColorConstants.BLACK;
        float fontSize = highlight ? baseFontSize * 1.2f : baseFontSize;

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

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private float mmToPt(int mm) {
        return mm * 72f / 25.4f;
    }
}
