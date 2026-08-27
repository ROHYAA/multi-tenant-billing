package com.mtbs.business.invoice.template;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.CompressionConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
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
import com.mtbs.business.payment.entity.Payment;
import com.mtbs.shared.enums.bill.PaymentMethod;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.settings.entity.ShopSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A4 renderer for the "Marathi Cash Memo" style — a boxed, Marathi-labeled
 * retail cash-memo layout (modeled on common mobile/electronics-shop bill
 * formats), as opposed to A4Renderer's English tax-invoice style. Every
 * business detail (name, address, GSTIN, bank details) is read from this
 * shop's own ShopSettings at render time — nothing about any specific real
 * shop is hardcoded here, only the template's fixed visual identity (Marathi
 * labels, maroon accent color, boxed sections).
 *
 * Registry key is built by BillPdfService as "{billTemplate.code}:A4" — see
 * V11__seed_marathi_cash_memo_template.sql for the MARATHI_CASH_MEMO_V1 row.
 *
 * Uses Mukta (bundled under src/main/resources/fonts/, OFL-licensed), the
 * first Unicode/Devanagari-capable font loaded anywhere in this PDF pipeline
 * — every other renderer only uses the built-in Latin-only Helvetica.
 */
@Component
@RequiredArgsConstructor
public class MarathiCashMemoA4Renderer implements BillTemplateRenderer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);

    private static final DeviceRgb ACCENT       = new DeviceRgb(139, 21, 71);
    private static final DeviceRgb ACCENT_LIGHT = new DeviceRgb(253, 235, 240);
    private static final DeviceRgb BORDER_COLOR = new DeviceRgb(139, 21, 71);
    private static final DeviceRgb MUTED_TEXT   = new DeviceRgb(90, 90, 90);

    /** Order shown in the Payment Mode row — matches PaymentMethod exactly. */
    private static final Map<PaymentMethod, String> PAYMENT_METHOD_LABELS = new LinkedHashMap<>();
    static {
        PAYMENT_METHOD_LABELS.put(PaymentMethod.CASH, "Cash");
        PAYMENT_METHOD_LABELS.put(PaymentMethod.UPI, "UPI");
        PAYMENT_METHOD_LABELS.put(PaymentMethod.CARD, "Card");
        PAYMENT_METHOD_LABELS.put(PaymentMethod.NETBANKING, "NetBanking");
        PAYMENT_METHOD_LABELS.put(PaymentMethod.BANK_TRANSFER, "Bank Transfer");
        PAYMENT_METHOD_LABELS.put(PaymentMethod.CREDIT, "Credit");
    }

    private final BillRenderSupport support;

    @Override
    public String code() {
        return "MARATHI_CASH_MEMO_V1:A4";
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
        document.setMargins(marginPt + 22, marginPt + 22, marginPt + 22, marginPt + 22);

        PdfFont regular = loadFont("/fonts/Mukta-Regular.ttf");
        PdfFont bold    = loadFont("/fonts/Mukta-Bold.ttf");

        addHeader(document, invoice, options, settings, bold, regular, baseFontSize);
        addBillMetaRow(document, invoice, customer, settings, bold, regular, baseFontSize);
        addItemsTable(document, items, bold, regular, baseFontSize);
        addTotals(document, invoice, settings, bold, regular, baseFontSize);
        addPaymentModeAndQr(document, invoice, settings, options, pdfDoc, bold, regular, baseFontSize);

        if (StringUtils.hasText(invoice.getNotes())) {
            document.add(new Paragraph(invoice.getNotes())
                    .setFont(regular).setFontSize(baseFontSize * 0.85f).setFontColor(MUTED_TEXT)
                    .setMarginBottom(6));
        }

        addBankDetails(document, settings, bold, regular, baseFontSize);
        addTermsAndSignature(document, settings, bold, regular, baseFontSize);

        support.drawWatermark(pdfDoc, settings);
        drawPageBorder(pdfDoc);

        document.close();
        return baos.toByteArray();
    }

    // ── Header: logo, shop name, neutral "CASH MEMO / BILL" subtitle, address ─

    private void addHeader(Document document, Bill invoice, BillRenderOptions options, ShopSettings settings,
                            PdfFont bold, PdfFont regular, float baseFontSize) {
        Table headerTable = new Table(UnitValue.createPercentArray(new float[]{70, 30}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(4);

        Cell nameCell = new Cell().setBorder(Border.NO_BORDER);
        Image logo = support.loadLogo(settings);
        if (logo != null) {
            logo.setMaxWidth(50).setMaxHeight(50);
            nameCell.add(logo);
        }
        nameCell.add(new Paragraph(
                StringUtils.hasText(settings.getBusinessName()) ? settings.getBusinessName() : "")
                .setFont(bold).setFontSize(baseFontSize * 2.0f).setFontColor(ACCENT).setMarginBottom(2));

        StringBuilder addressLine = new StringBuilder();
        if (settings.getAddress() != null) addressLine.append(settings.getAddress());
        String cityStateLine = String.join(", ",
                        nullToEmpty(settings.getCity()), nullToEmpty(settings.getState()), nullToEmpty(settings.getPincode()))
                .replaceAll("(, )+", ", ").replaceAll("^, |, $", "");
        if (!cityStateLine.isBlank()) {
            if (!addressLine.isEmpty()) addressLine.append(", ");
            addressLine.append(cityStateLine);
        }
        if (settings.getMobile() != null) {
            if (!addressLine.isEmpty()) addressLine.append("  ");
            addressLine.append("मो. ").append(settings.getMobile());
        }
        if (!addressLine.isEmpty()) {
            nameCell.add(new Paragraph(addressLine.toString())
                    .setFont(regular).setFontSize(baseFontSize * 0.85f).setFontColor(MUTED_TEXT));
        }
        headerTable.addCell(nameCell);

        Cell memoCell = new Cell()
                .add(new Paragraph("CASH MEMO / BILL")
                        .setFont(bold).setFontSize(baseFontSize * 1.0f).setFontColor(ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.CENTER))
                .setBackgroundColor(ACCENT)
                .setBorder(Border.NO_BORDER)
                .setPadding(8)
                .setVerticalAlignment(com.itextpdf.layout.properties.VerticalAlignment.MIDDLE);
        String copyLabel = copyLabelText(options);
        if (copyLabel != null) {
            memoCell.add(new Paragraph(copyLabel)
                    .setFont(regular).setFontSize(baseFontSize * 0.7f).setFontColor(ColorConstants.WHITE)
                    .setTextAlignment(TextAlignment.CENTER).setMarginTop(2));
        }
        headerTable.addCell(memoCell);

        document.add(headerTable);
        document.add(new LineSeparator(new SolidLine(1.2f)).setStrokeColor(ACCENT).setMarginBottom(6));
    }

    /** Plain ORIGINAL / DUPLICATE / TRIPLICATE — not BillRenderSupport's "...FOR RECIPIENT/SUPPLIER" long form. */
    private String copyLabelText(BillRenderOptions options) {
        if (options == null || options.copyType() == null) {
            return null;
        }
        return options.copyType().name();
    }

    // ── Bill meta row: नांव/पत्ता/मोबा. नं. (left) | बिल नं./दि. (right) ──────

    private void addBillMetaRow(Document document, Bill invoice, Customer customer, ShopSettings settings,
                                 PdfFont bold, PdfFont regular, float baseFontSize) {
        Table metaTable = new Table(UnitValue.createPercentArray(new float[]{65, 35}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(6);

        Cell customerCell = new Cell().setBorder(new SolidBorder(BORDER_COLOR, 0.75f)).setPadding(8);
        customerCell.add(fieldLine("नांव", customer.getName(), bold, regular, baseFontSize));
        if (Boolean.TRUE.equals(settings.getShowCustomerAddress()) && StringUtils.hasText(customer.getAddress())) {
            customerCell.add(fieldLine("पत्ता", customer.getAddress(), bold, regular, baseFontSize));
        }
        if (StringUtils.hasText(customer.getPhone())) {
            customerCell.add(fieldLine("मोबा. नं.", customer.getPhone(), bold, regular, baseFontSize));
        }
        metaTable.addCell(customerCell);

        Cell billInfoCell = new Cell().setBorder(new SolidBorder(BORDER_COLOR, 0.75f)).setPadding(8);
        billInfoCell.add(fieldLine("बिल नं.", invoice.getInvoiceNumber(), bold, regular, baseFontSize));
        billInfoCell.add(fieldLine("दि.", formatInstant(invoice.getCreatedAt()), bold, regular, baseFontSize));
        metaTable.addCell(billInfoCell);

        document.add(metaTable);
    }

    private Paragraph fieldLine(String label, String value, PdfFont bold, PdfFont regular, float baseFontSize) {
        return new Paragraph()
                .add(new com.itextpdf.layout.element.Text(label + " : ").setFont(bold).setFontSize(baseFontSize * 0.85f))
                .add(new com.itextpdf.layout.element.Text(value != null ? value : "").setFont(regular).setFontSize(baseFontSize * 0.85f))
                .setMarginBottom(2);
    }

    // ── Items table: अ.नं. | तपशील | नग | दर | आकार ───────────────────────────

    private void addItemsTable(Document document, List<BillItem> items, PdfFont bold, PdfFont regular, float baseFontSize) {
        Table table = new Table(UnitValue.createPercentArray(new float[]{8, 46, 12, 16, 18}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(6)
                .setBorder(new SolidBorder(BORDER_COLOR, 0.75f));

        String[] headers = {"अ.नं.", "तपशील", "नग", "दर", "आकार"};
        TextAlignment[] headerAlign = {TextAlignment.CENTER, TextAlignment.LEFT, TextAlignment.CENTER, TextAlignment.RIGHT, TextAlignment.RIGHT};
        for (int i = 0; i < headers.length; i++) {
            table.addHeaderCell(new Cell()
                    .add(new Paragraph(headers[i]).setFont(bold).setFontSize(baseFontSize * 0.95f).setFontColor(ColorConstants.WHITE))
                    .setBackgroundColor(ACCENT)
                    .setBorder(new SolidBorder(BORDER_COLOR, 0.75f))
                    .setTextAlignment(headerAlign[i])
                    .setPadding(6));
        }

        int rowNum = 1;
        for (BillItem item : items) {
            table.addCell(itemCell(String.valueOf(rowNum++), regular, TextAlignment.CENTER, baseFontSize));
            table.addCell(itemCell(item.getDescription(), regular, TextAlignment.LEFT, baseFontSize));
            table.addCell(itemCell(item.getQuantity().toPlainString(), regular, TextAlignment.CENTER, baseFontSize));
            table.addCell(itemCell(fmt(item.getUnitPrice()), regular, TextAlignment.RIGHT, baseFontSize));
            table.addCell(itemCell(fmt(item.getUnitPrice().multiply(item.getQuantity()).setScale(2, RoundingMode.HALF_UP)),
                    regular, TextAlignment.RIGHT, baseFontSize));
        }

        document.add(table);
    }

    private Cell itemCell(String text, PdfFont font, TextAlignment align, float baseFontSize) {
        return new Cell()
                .add(new Paragraph(text).setFont(font).setFontSize(baseFontSize * 0.9f))
                .setBorder(new SolidBorder(BORDER_COLOR, 0.5f))
                .setPadding(4)
                .setTextAlignment(align);
    }

    // ── Totals: एकूण / सूट / सी जीएसटी / एस जीएसटी / एकूण रक्कम ──────────────

    private void addTotals(Document document, Bill invoice, ShopSettings settings, PdfFont bold, PdfFont regular, float baseFontSize) {
        Table wrapper = new Table(UnitValue.createPercentArray(new float[]{55, 45}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(6);
        wrapper.addCell(new Cell().setBorder(Border.NO_BORDER));

        Table totals = new Table(UnitValue.createPercentArray(new float[]{55, 45}))
                .setWidth(UnitValue.createPercentValue(100))
                .setBorder(new SolidBorder(BORDER_COLOR, 0.75f));

        addTotalRow(totals, "एकूण", fmt(invoice.getSubtotal()), regular, false, baseFontSize);

        BigDecimal discount = invoice.getDiscountAmount();
        if (discount != null && discount.compareTo(BigDecimal.ZERO) > 0) {
            addTotalRow(totals, "सूट", "− " + fmt(discount), regular, false, baseFontSize);
        }

        if (Boolean.TRUE.equals(settings.getShowGst())) {
            BigDecimal halfTax = invoice.getTaxAmount() != null
                    ? invoice.getTaxAmount().divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            // GST terminology stays in English even on Marathi bills — it's a legal/tax
            // term, not something naturally transliterated ("सी जीएसटी" reads as a
            // broken half-transliteration, not real Marathi).
            addTotalRow(totals, "CGST", fmt(halfTax), regular, false, baseFontSize);
            addTotalRow(totals, "SGST", fmt(halfTax), regular, false, baseFontSize);
        }

        addTotalRow(totals, "एकूण रक्कम", fmt(invoice.getTotalAmount()), bold, true, baseFontSize);

        wrapper.addCell(new Cell().add(totals).setBorder(Border.NO_BORDER).setPadding(0));
        document.add(wrapper);

        if (Boolean.TRUE.equals(settings.getShowAmountInWords())) {
            String words = support.amountInWords(invoice.getTotalAmount(), invoice.getCurrency());
            document.add(new Paragraph("Amount in words: " + words)
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT).setMarginBottom(6));
        }
    }

    private void addTotalRow(Table table, String label, String value, PdfFont font, boolean highlight, float baseFontSize) {
        float fontSize = highlight ? baseFontSize * 1.05f : baseFontSize * 0.9f;
        table.addCell(new Cell()
                .add(new Paragraph(label).setFont(font).setFontSize(fontSize))
                .setBackgroundColor(highlight ? ACCENT_LIGHT : null)
                .setBorder(new SolidBorder(BORDER_COLOR, 0.5f)).setPadding(4));
        table.addCell(new Cell()
                .add(new Paragraph(value).setFont(font).setFontSize(fontSize))
                .setBackgroundColor(highlight ? ACCENT_LIGHT : null)
                .setBorder(new SolidBorder(BORDER_COLOR, 0.5f)).setPadding(4)
                .setTextAlignment(TextAlignment.RIGHT));
    }

    // ── Payment Mode (clear payment section) + UPI QR ─────────────────────────

    private void addPaymentModeAndQr(Document document, Bill invoice, ShopSettings settings, BillRenderOptions options,
                                      PdfDocument pdfDoc, PdfFont bold, PdfFont regular, float baseFontSize) {
        // A PENDING credit payment is a promise, not collected cash — don't
        // highlight its method chip as if it were actually paid.
        Set<PaymentMethod> usedMethods = options.payments().stream()
                .filter(p -> p.getStatus() == com.mtbs.shared.enums.bill.PaymentStatus.CONFIRMED)
                .map(Payment::getMethod)
                .collect(Collectors.toSet());

        Image qr = support.generateUpiQrCode(invoice, settings, pdfDoc);

        Table row = new Table(UnitValue.createPercentArray(qr != null ? new float[]{75, 25} : new float[]{100}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(6);

        Paragraph paymentLine = new Paragraph()
                .add(new com.itextpdf.layout.element.Text("पेमेंट पद्धत : ")
                        .setFont(bold).setFontSize(baseFontSize * 0.85f));
        for (Map.Entry<PaymentMethod, String> entry : PAYMENT_METHOD_LABELS.entrySet()) {
            boolean isUsed = usedMethods.contains(entry.getKey());
            com.itextpdf.layout.element.Text chip = new com.itextpdf.layout.element.Text(" " + entry.getValue() + " ")
                    .setFont(isUsed ? bold : regular)
                    .setFontSize(baseFontSize * 0.78f)
                    .setFontColor(isUsed ? ColorConstants.WHITE : MUTED_TEXT);
            if (isUsed) {
                chip.setBackgroundColor(ACCENT);
            }
            paymentLine.add(chip);
        }
        row.addCell(new Cell().add(paymentLine).setBorder(Border.NO_BORDER).setPadding(0)
                .setVerticalAlignment(com.itextpdf.layout.properties.VerticalAlignment.MIDDLE));

        if (qr != null) {
            qr.setMaxWidth(46).setMaxHeight(46);
            Cell qrCell = new Cell()
                    .add(qr)
                    .add(new Paragraph("स्कॅन करा").setFont(regular).setFontSize(baseFontSize * 0.65f)
                            .setFontColor(MUTED_TEXT).setTextAlignment(TextAlignment.CENTER).setMarginTop(1))
                    .setBorder(Border.NO_BORDER)
                    .setPadding(0)
                    .setTextAlignment(TextAlignment.CENTER);
            row.addCell(qrCell);
        }

        document.add(row);
    }

    // ── Bank details footer — only when the shop has set one ─────────────────

    private void addBankDetails(Document document, ShopSettings settings, PdfFont bold, PdfFont regular, float baseFontSize) {
        if (!StringUtils.hasText(settings.getBankName())) {
            return;
        }

        StringBuilder line1 = new StringBuilder("Bank Details : ").append(settings.getBankName());
        if (StringUtils.hasText(settings.getBankBranch())) {
            line1.append(", ").append(settings.getBankBranch());
        }
        document.add(new Paragraph(line1.toString())
                .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT).setMarginBottom(1));

        StringBuilder line2 = new StringBuilder();
        if (StringUtils.hasText(settings.getBankIfsc())) {
            line2.append("IFSC : ").append(settings.getBankIfsc());
        }
        if (StringUtils.hasText(settings.getBankAccountNo())) {
            if (!line2.isEmpty()) line2.append("   ");
            line2.append("A/c No. ").append(settings.getBankAccountNo());
        }
        if (!line2.isEmpty()) {
            document.add(new Paragraph(line2.toString())
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT).setMarginBottom(1));
        }

        if (Boolean.TRUE.equals(settings.getShowGst()) && StringUtils.hasText(settings.getGstin())) {
            document.add(new Paragraph("GSTIN : " + settings.getGstin())
                    .setFont(bold).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT).setMarginBottom(6));
        }
    }

    // ── Terms (shop's own text) + signature line ──────────────────────────────

    private void addTermsAndSignature(Document document, ShopSettings settings, PdfFont bold, PdfFont regular, float baseFontSize) {
        document.add(new LineSeparator(new SolidLine(0.75f)).setStrokeColor(ACCENT).setMarginTop(2).setMarginBottom(4));

        Table footerTable = new Table(UnitValue.createPercentArray(new float[]{65, 35}))
                .setWidth(UnitValue.createPercentValue(100));

        Cell termsCell = new Cell().setBorder(Border.NO_BORDER);
        if (StringUtils.hasText(settings.getTermsAndConditions())) {
            termsCell.add(new Paragraph(settings.getTermsAndConditions())
                    .setFont(regular).setFontSize(baseFontSize * 0.7f).setFontColor(MUTED_TEXT));
        }
        if (StringUtils.hasText(settings.getWarrantyText())) {
            termsCell.add(new Paragraph(settings.getWarrantyText())
                    .setFont(regular).setFontSize(baseFontSize * 0.7f).setFontColor(MUTED_TEXT));
        }
        footerTable.addCell(termsCell);

        Cell signCell = new Cell().setBorder(Border.NO_BORDER).setTextAlignment(TextAlignment.CENTER);
        if (Boolean.TRUE.equals(settings.getShowSignature())) {
            Image signatureImage = support.loadSignatureImage(settings);
            if (signatureImage != null) {
                signatureImage.setMaxWidth(100).setMaxHeight(40);
                signatureImage.setHorizontalAlignment(HorizontalAlignment.CENTER);
                signCell.add(signatureImage);
            } else {
                signCell.add(new Paragraph("\n").setFontSize(baseFontSize * 1.5f));
            }
        } else {
            signCell.add(new Paragraph("\n").setFontSize(baseFontSize * 1.5f));
        }
        signCell.add(new Paragraph(" सही")
                .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT));
        if (StringUtils.hasText(settings.getBusinessName())) {
            // "[Shop Name] तर्फे" = "For [Shop Name]" — the standard phrase above a
            // signature line on Indian invoices. The previous "...करितो." ("...does")
            // was a dangling verb with no subject/object — not a grammatical sentence.
            signCell.add(new Paragraph(settings.getBusinessName() + "")
                    .setFont(bold).setFontSize(baseFontSize * 0.85f).setFontColor(ACCENT));
        }
        footerTable.addCell(signCell);

        document.add(footerTable);

        if (StringUtils.hasText(settings.getFooterMessage())) {
            document.add(new Paragraph(settings.getFooterMessage())
                    .setFont(regular).setFontSize(baseFontSize * 0.8f).setFontColor(MUTED_TEXT)
                    .setTextAlignment(TextAlignment.CENTER).setMarginTop(6));
        }
    }

    // ── Page border — the boxed look the reference photo has ─────────────────

    private void drawPageBorder(PdfDocument pdfDoc) {
        for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
            PdfPage page = pdfDoc.getPage(i);
            Rectangle box = page.getPageSize().clone();
            float inset = 14f;
            box.setX(box.getX() + inset);
            box.setY(box.getY() + inset);
            box.setWidth(box.getWidth() - 2 * inset);
            box.setHeight(box.getHeight() - 2 * inset);

            PdfCanvas canvas = new PdfCanvas(page);
            canvas.saveState();
            canvas.setStrokeColor(ACCENT);
            canvas.setLineWidth(1.2f);
            canvas.rectangle(box);
            canvas.stroke();
            canvas.restoreState();
        }
    }

    // ── Font loading — Mukta (Devanagari + Latin), first Unicode font in this pipeline ─

    private PdfFont loadFont(String classpathResource) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IOException("Font resource not found: " + classpathResource);
            }
            byte[] bytes = is.readAllBytes();
            return PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
        }
    }

    private String fmt(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00";
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
