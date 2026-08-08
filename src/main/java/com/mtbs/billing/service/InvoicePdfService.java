package com.mtbs.billing.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.mtbs.billing.entity.Invoice;
import com.mtbs.billing.entity.InvoiceLineItem;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.repository.InvoiceLineItemRepository;
import com.mtbs.billing.repository.InvoiceRepository;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository lineItemRepository;
    private final SubscriptionService subscriptionService;
    private final UsageService usageService;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.of("Asia/Kolkata"));

    public byte[] generatePdf(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ResourceException.notFound("Invoice", invoiceId));
        List<InvoiceLineItem> lineItems = lineItemRepository.findByInvoiceId(invoiceId);

        byte[] pdfBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdfDoc = new PdfDocument(writer);
            Document document = new Document(pdfDoc);

            document.add(new Paragraph("INVOICE")
                    .setFontSize(24)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER));

            document.add(new Paragraph("\n"));

            document.add(new Paragraph("Invoice Number: " + invoice.getInvoiceNumber())
                    .setFontSize(12));
            document.add(new Paragraph("Status: " + invoice.getStatus().name())
                    .setFontSize(12));

            if (invoice.getBillingPeriodStart() != null && invoice.getBillingPeriodEnd() != null) {
                document.add(new Paragraph("Billing Period: " +
                        DATE_FORMATTER.format(invoice.getBillingPeriodStart()) + " - " +
                        DATE_FORMATTER.format(invoice.getBillingPeriodEnd()))
                        .setFontSize(12));
            }

            if (invoice.getDueDate() != null) {
                document.add(new Paragraph("Due Date: " + DATE_FORMATTER.format(invoice.getDueDate()))
                        .setFontSize(12));
            }

            document.add(new Paragraph("\n"));

            float[] columnWidths = {4, 1, 2, 2};
            Table table = new Table(UnitValue.createPercentArray(columnWidths));
            table.setWidth(UnitValue.createPercentValue(100));

            table.addHeaderCell(new Cell().add(new Paragraph("Description").setBold()));
            table.addHeaderCell(new Cell().add(new Paragraph("Qty").setBold()));
            table.addHeaderCell(new Cell().add(new Paragraph("Unit Price").setBold()));
            table.addHeaderCell(new Cell().add(new Paragraph("Total").setBold()));

            for (InvoiceLineItem item : lineItems) {
                table.addCell(new Cell().add(new Paragraph(item.getDescription())));
                table.addCell(new Cell().add(new Paragraph(item.getQuantity().toPlainString())));
                table.addCell(new Cell().add(new Paragraph(invoice.getCurrency() + " " + item.getUnitPrice().toPlainString())));
                table.addCell(new Cell().add(new Paragraph(invoice.getCurrency() + " " + item.getTotalPrice().toPlainString())));
            }

            document.add(table);
            document.add(new Paragraph("\n"));

            document.add(new Paragraph("Subtotal: " + invoice.getCurrency() + " " +
                    invoice.getSubtotal().toPlainString())
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setFontSize(12));

            if (invoice.getTaxAmount().signum() > 0) {
                document.add(new Paragraph("Tax: " + invoice.getCurrency() + " " +
                        invoice.getTaxAmount().toPlainString())
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setFontSize(12));
            }

            if (invoice.getDiscountAmount().signum() > 0) {
                document.add(new Paragraph("Discount: -" + invoice.getCurrency() + " " +
                        invoice.getDiscountAmount().toPlainString())
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setFontSize(12));
            }

            document.add(new Paragraph("Total: " + invoice.getCurrency() + " " +
                    invoice.getTotalAmount().toPlainString())
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setFontSize(14)
                    .setBold());

            document.add(new Paragraph("\n"));
            String paymentStatus = invoice.getPaidAt() != null ?
                    "PAID on " + DATE_FORMATTER.format(invoice.getPaidAt()) :
                    "Payment Status: " + invoice.getStatus().name();
            document.add(new Paragraph(paymentStatus)
                    .setFontSize(12)
                    .setTextAlignment(TextAlignment.CENTER));

            document.close();

            pdfBytes = baos.toByteArray();
            log.info("Generated PDF for invoice: {}", invoice.getInvoiceNumber());

        } catch (Exception e) {
            log.error("Failed to generate PDF for invoice: {}", invoiceId, e);
            throw ResourceException.invalid("Failed to generate PDF: " + e.getMessage());
        }

        recordStorageUsageAsync(invoice.getSubscriptionId(), pdfBytes.length);

        return pdfBytes;
    }

    @Async
    public void recordStorageUsageAsync(Long subscriptionId, long fileSizeBytes) {
        try {
            Optional<Subscription> subOpt = subscriptionService.findSubscriptionById(subscriptionId);

            if (subOpt.isEmpty()) {
                log.warn("No subscription found for subscriptionId={} — skipping storage recording", subscriptionId);
                return;
            }
            Subscription subscription = subOpt.get();
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                log.warn("No tenant context — skipping storage recording for subscriptionId={}", subscriptionId);
                return;
            }

            usageService.recordStorageUsage(
                    tenantId,
                    subscriptionId,
                    fileSizeBytes,
                    subscription.getCurrentPeriodStart(),
                    subscription.getCurrentPeriodEnd()
            );
            log.debug("Storage usage recorded for invoice subscriptionId={}, bytes={}", subscriptionId, fileSizeBytes);
        } catch (Exception e) {
            log.error("Failed to record storage usage for subscription {}: {}", subscriptionId, e.getMessage(), e);
        }
    }
}
