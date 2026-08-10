package com.mtbs.business.invoice.template;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.CompressionConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.pdf.canvas.draw.DashedLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.LineSeparator;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.mtbs.business.customer.entity.Customer;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.business.invoice.entity.BillItem;
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
 * Shared content-drawing logic for Thermal58Renderer/Thermal80Renderer —
 * both are the same single-column receipt layout, differing only in paper
 * width. Kept as one injected helper (rather than duplicating the layout
 * in two classes, or an inheritance hierarchy) so the two @Component
 * renderer classes the interface requires stay thin.
 *
 * Page height is a generous fixed value, not dynamically measured from
 * content — real thermal printers feed a continuous roll and cut based on
 * their own logic; a true content-height-trimmed PDF (two-pass measure-
 * then-render) is a known refinement not implemented in this pass.
 */
@Component
@RequiredArgsConstructor
class ThermalLayoutBuilder {

    private static final float FIXED_HEIGHT_PT = 2000f;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yy, HH:mm").withZone(ZoneOffset.UTC);
    private static final DeviceRgb MUTED_TEXT = new DeviceRgb(90, 90, 90);

    private final BillRenderSupport support;

    byte[] build(float widthMm, Bill invoice, List<BillItem> items, Customer customer,
                 ShopSettings settings, BillRenderOptions options) throws Exception {

        float baseFontSize = settings.getFontSize() != null ? Math.min(settings.getFontSize(), 11) : 9;
        float marginPt = mmToPt(settings.getMargin() != null ? Math.min(settings.getMargin(), 5) : 3);
        float widthPt = mmToPt((int) widthMm);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WriterProperties writerProperties = new WriterProperties()
                .setCompressionLevel(CompressionConstants.BEST_COMPRESSION)
                .setFullCompressionMode(true);
        PdfWriter writer   = new PdfWriter(baos, writerProperties);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document  = new Document(pdfDoc, new PageSize(widthPt, FIXED_HEIGHT_PT));
        document.setMargins(marginPt, marginPt, marginPt, marginPt);

        PdfFont regular = PdfFontFactory.createFont("Helvetica");
        PdfFont bold    = PdfFontFactory.createFont("Helvetica-Bold");

        addBusinessHeader(document, settings, bold, regular, baseFontSize);
        addInvoiceMeta(document, invoice, options, regular, bold, baseFontSize);
        addCustomer(document, customer, settings, regular, bold, baseFontSize);
        divider(document);
        addItems(document, items, regular, bold, baseFontSize);
        divider(document);
        addTotals(document, invoice, settings, regular, bold, baseFontSize);
        addQrCode(document, invoice, settings, pdfDoc, regular, baseFontSize);
        addSignature(document, settings, regular, baseFontSize);
        addFooter(document, invoice, settings, regular, baseFontSize);

        support.drawWatermark(pdfDoc, settings);

        document.close();
        return baos.toByteArray();
    }

    private void addBusinessHeader(Document document, ShopSettings settings, PdfFont bold, PdfFont regular, float baseFontSize) {
        Image logo = support.loadLogo(settings);
        if (logo != null) {
            logo.setMaxWidth(60).setMaxHeight(40).setHorizontalAlignment(HorizontalAlignment.CENTER);
            document.add(logo);
        }

        if (settings.getBusinessName() != null) {
            document.add(new Paragraph(settings.getBusinessName())
                    .setFont(bold).setFontSize(baseFontSize * 1.3f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(2));
        }
        if (settings.getAddress() != null) {
            document.add(new Paragraph(settings.getAddress())
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(1));
        }
        if (settings.getMobile() != null) {
            document.add(new Paragraph(settings.getMobile())
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(1));
        }
        if (Boolean.TRUE.equals(settings.getShowGst()) && settings.getGstin() != null) {
            document.add(new Paragraph("GSTIN: " + settings.getGstin())
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(6));
        }
    }

    private void addInvoiceMeta(Document document, Bill invoice, BillRenderOptions options, PdfFont regular, PdfFont bold, float baseFontSize) {
        String copyLabel = support.copyLabelText(options);
        if (copyLabel != null) {
            document.add(new Paragraph(copyLabel)
                    .setFont(bold).setFontSize(baseFontSize * 0.8f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(4));
        }
        document.add(new Paragraph(invoice.getInvoiceNumber())
                .setFont(bold).setFontSize(baseFontSize)
                .setTextAlignment(TextAlignment.CENTER));
        document.add(new Paragraph(formatInstant(invoice.getCreatedAt()))
                .setFont(regular).setFontSize(baseFontSize * 0.85f).setFontColor(MUTED_TEXT)
                .setTextAlignment(TextAlignment.CENTER).setMarginBottom(6));
    }

    private void addCustomer(Document document, Customer customer, ShopSettings settings, PdfFont regular, PdfFont bold, float baseFontSize) {
        document.add(new Paragraph(customer.getName())
                .setFont(bold).setFontSize(baseFontSize).setMarginBottom(1));
        if (customer.getPhone() != null) {
            document.add(new Paragraph(customer.getPhone())
                    .setFont(regular).setFontSize(baseFontSize * 0.85f).setFontColor(MUTED_TEXT).setMarginBottom(1));
        }
        if (Boolean.TRUE.equals(settings.getShowCustomerAddress()) && customer.getAddress() != null) {
            document.add(new Paragraph(customer.getAddress())
                    .setFont(regular).setFontSize(baseFontSize * 0.85f).setFontColor(MUTED_TEXT));
        }
    }

    private void addItems(Document document, List<BillItem> items, PdfFont regular, PdfFont bold, float baseFontSize) {
        for (BillItem item : items) {
            document.add(new Paragraph(item.getDescription())
                    .setFont(bold).setFontSize(baseFontSize * 0.9f).setMarginBottom(0));

            Table row = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                    .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(4);
            row.addCell(borderless(new Paragraph(item.getQuantity().toPlainString() + " x " + fmt(item.getUnitPrice()))
                    .setFont(regular).setFontSize(baseFontSize * 0.85f).setFontColor(MUTED_TEXT)));
            row.addCell(borderless(new Paragraph(fmt(item.getTotal()))
                    .setFont(regular).setFontSize(baseFontSize * 0.9f))
                    .setTextAlignment(TextAlignment.RIGHT));
            document.add(row);
        }
    }

    private void addTotals(Document document, Bill invoice, ShopSettings settings, PdfFont regular, PdfFont bold, float baseFontSize) {
        totalRow(document, "Subtotal", fmt(invoice.getSubtotal()), regular, baseFontSize * 0.9f);
        totalRow(document, "Tax", fmt(invoice.getTaxAmount()), regular, baseFontSize * 0.9f);
        totalRow(document, "TOTAL (" + invoice.getCurrency() + ")", fmt(invoice.getTotalAmount()), bold, baseFontSize * 1.1f);

        if (Boolean.TRUE.equals(settings.getShowAmountInWords())) {
            String words = support.amountInWords(invoice.getTotalAmount(), invoice.getCurrency());
            document.add(new Paragraph(words)
                    .setFont(regular).setFontSize(baseFontSize * 0.75f).setFontColor(MUTED_TEXT)
                    .setMarginTop(4).setMarginBottom(6));
        }
    }

    private void addQrCode(Document document, Bill invoice, ShopSettings settings, PdfDocument pdfDoc, PdfFont regular, float baseFontSize) {
        Image qr = support.generateUpiQrCode(invoice, settings, pdfDoc);
        if (qr == null) {
            return;
        }
        qr.setMaxWidth(80).setMaxHeight(80).setHorizontalAlignment(HorizontalAlignment.CENTER);
        document.add(qr);
        document.add(new Paragraph("Scan to pay")
                .setFont(regular).setFontSize(baseFontSize * 0.75f).setFontColor(MUTED_TEXT)
                .setTextAlignment(TextAlignment.CENTER).setMarginBottom(6));
    }

    private void addSignature(Document document, ShopSettings settings, PdfFont regular, float baseFontSize) {
        if (!Boolean.TRUE.equals(settings.getShowSignature())) {
            return;
        }
        Image signatureImage = support.loadSignatureImage(settings);
        if (signatureImage != null) {
            signatureImage.setMaxWidth(80).setMaxHeight(35).setHorizontalAlignment(HorizontalAlignment.CENTER);
            document.add(signatureImage);
        } else {
            document.add(new Paragraph("\n_______________")
                    .setFont(regular).setFontSize(baseFontSize * 0.85f)
                    .setTextAlignment(TextAlignment.CENTER).setMarginTop(10));
        }
        document.add(new Paragraph("Authorized Signatory")
                .setFont(regular).setFontSize(baseFontSize * 0.75f).setFontColor(MUTED_TEXT)
                .setTextAlignment(TextAlignment.CENTER));
    }

    private void addFooter(Document document, Bill invoice, ShopSettings settings, PdfFont regular, float baseFontSize) {
        if (settings.getTermsAndConditions() != null && !settings.getTermsAndConditions().isBlank()) {
            document.add(new Paragraph(settings.getTermsAndConditions())
                    .setFont(regular).setFontSize(baseFontSize * 0.7f).setFontColor(MUTED_TEXT)
                    .setTextAlignment(TextAlignment.CENTER).setMarginTop(6));
        }
        if (settings.getWarrantyText() != null && !settings.getWarrantyText().isBlank()) {
            document.add(new Paragraph(settings.getWarrantyText())
                    .setFont(regular).setFontSize(baseFontSize * 0.7f).setFontColor(MUTED_TEXT)
                    .setTextAlignment(TextAlignment.CENTER));
        }
        String closing = settings.getFooterMessage() != null && !settings.getFooterMessage().isBlank()
                ? settings.getFooterMessage()
                : "Thank you!";
        document.add(new Paragraph(closing)
                .setFont(regular).setFontSize(baseFontSize * 0.85f).setFontColor(MUTED_TEXT)
                .setTextAlignment(TextAlignment.CENTER).setMarginTop(6));
    }

    private void divider(Document document) {
        document.add(new LineSeparator(new DashedLine(0.5f)).setMarginTop(4).setMarginBottom(4));
    }

    private void totalRow(Document document, String label, String value, PdfFont font, float fontSize) {
        Table row = new Table(UnitValue.createPercentArray(new float[]{50, 50}))
                .setWidth(UnitValue.createPercentValue(100)).setMarginBottom(2);
        row.addCell(borderless(new Paragraph(label).setFont(font).setFontSize(fontSize)));
        row.addCell(borderless(new Paragraph(value).setFont(font).setFontSize(fontSize))
                .setTextAlignment(TextAlignment.RIGHT));
        document.add(row);
    }

    private com.itextpdf.layout.element.Cell borderless(Paragraph content) {
        return new com.itextpdf.layout.element.Cell()
                .add(content)
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .setPadding(0);
    }

    private String fmt(BigDecimal value) {
        return value != null ? value.toPlainString() : "0.00";
    }

    private String formatInstant(Instant instant) {
        return instant != null ? DATE_FMT.format(instant) : "-";
    }

    private float mmToPt(int mm) {
        return mm * 72f / 25.4f;
    }
}
