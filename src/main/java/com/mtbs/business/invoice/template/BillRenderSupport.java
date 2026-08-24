package com.mtbs.business.invoice.template;

import com.itextpdf.barcodes.BarcodeQRCode;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceGray;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.layout.element.Image;
import com.mtbs.business.invoice.entity.Bill;
import com.mtbs.tenant.attachment.service.AttachmentService;
import com.mtbs.tenant.settings.entity.ShopSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Rendering helpers shared across A4Renderer/Thermal58Renderer/Thermal80Renderer
 * — logo/signature image loading, UPI QR generation, watermark drawing, and
 * amount-in-words. Kept as composition (injected into each renderer) rather
 * than a base class, matching this codebase's general preference for flat
 * service composition over inheritance.
 *
 * Every method here fails soft: a missing/corrupt/unsupported-format image
 * (e.g. a WebP logo — the JDK's ImageIO has no built-in WebP decoder, and
 * iText's ImageDataFactory doesn't support it either) logs a warning and
 * returns null/skips, rather than failing the whole bill print. Printing a
 * bill without a logo is always better than not printing it at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class BillRenderSupport {

    private static final int MAX_IMAGE_DIMENSION_PX = 300;

    private final AttachmentService attachmentService;

    Image loadLogo(ShopSettings settings) {
        if (!Boolean.TRUE.equals(settings.getShowLogo()) || settings.getLogoAttachmentId() == null) {
            return null;
        }
        return loadImage(settings.getLogoAttachmentId());
    }

    Image loadSignatureImage(ShopSettings settings) {
        if (!Boolean.TRUE.equals(settings.getShowSignature()) || settings.getSignatureAttachmentId() == null) {
            return null;
        }
        return loadImage(settings.getSignatureAttachmentId());
    }

    private Image loadImage(Long attachmentId) {
        try {
            byte[] bytes = attachmentService.getFileBytes(attachmentId);
            byte[] optimized = downscaleIfNeeded(bytes, MAX_IMAGE_DIMENSION_PX);
            return new Image(ImageDataFactory.create(optimized));
        } catch (Exception e) {
            log.warn("Could not embed attachment id={} in PDF — skipping: {}", attachmentId, e.getMessage());
            return null;
        }
    }

    /**
     * Standard UPI deep-link format: upi://pay?pa=<vpa>&pn=<name>&am=<amount>&cu=<currency>&tn=<note>
     * Skipped (returns null) when showQrCode is off or no UPI ID is configured —
     * there is nothing sensible to encode otherwise.
     */
    Image generateUpiQrCode(Bill invoice, ShopSettings settings, PdfDocument pdfDoc) {
        if (!Boolean.TRUE.equals(settings.getShowQrCode()) || !StringUtils.hasText(settings.getUpiId())) {
            return null;
        }
        try {
            String payeeName = StringUtils.hasText(settings.getBusinessName()) ? settings.getBusinessName() : "Shop";
            String upiLink = "upi://pay"
                    + "?pa=" + encode(settings.getUpiId())
                    + "&pn=" + encode(payeeName)
                    + "&am=" + invoice.getTotalAmount().setScale(2, java.math.RoundingMode.HALF_UP)
                    + "&cu=" + encode(invoice.getCurrency() != null ? invoice.getCurrency() : "INR")
                    + "&tn=" + encode(invoice.getInvoiceNumber());

            BarcodeQRCode qrCode = new BarcodeQRCode(upiLink);
            return new Image(qrCode.createFormXObject(ColorConstants.BLACK, pdfDoc));
        } catch (Exception e) {
            log.warn("Could not generate UPI QR for invoice={} — skipping: {}", invoice.getInvoiceNumber(), e.getMessage());
            return null;
        }
    }

    String amountInWords(BigDecimal amount, String currencyCode) {
        return AmountToWordsConverter.convert(amount, currencyCode);
    }

    /** "ORIGINAL FOR RECIPIENT" / "DUPLICATE FOR SUPPLIER" / "TRIPLICATE FOR TRANSPORTER" — null when no copy type given. */
    String copyLabelText(BillRenderOptions options) {
        if (options == null || options.copyType() == null) {
            return null;
        }
        return switch (options.copyType()) {
            case ORIGINAL -> "ORIGINAL FOR RECIPIENT";
            case DUPLICATE -> "DUPLICATE FOR SUPPLIER";
            case TRIPLICATE -> "TRIPLICATE FOR TRANSPORTER";
        };
    }

    /** Draws a diagonal, translucent watermark across every page. Call just before document.close(). */
    void drawWatermark(PdfDocument pdfDoc, ShopSettings settings) {
        if (!StringUtils.hasText(settings.getWatermarkText())) {
            return;
        }
        try {
            PdfFont font = PdfFontFactory.createFont("Helvetica-Bold");
            for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
                PdfPage page = pdfDoc.getPage(i);
                Rectangle pageSize = page.getPageSize();
                PdfCanvas canvas = new PdfCanvas(page);

                PdfExtGState gState = new PdfExtGState().setFillOpacity(0.06f);
                canvas.saveState();
                canvas.setExtGState(gState);
                canvas.setFillColor(DeviceGray.BLACK);
                canvas.beginText();
                canvas.setFontAndSize(font, Math.min(pageSize.getWidth(), pageSize.getHeight()) / 9);
                canvas.setTextMatrix(
                        (float) Math.cos(Math.toRadians(45)), (float) Math.sin(Math.toRadians(45)),
                        (float) -Math.sin(Math.toRadians(45)), (float) Math.cos(Math.toRadians(45)),
                        pageSize.getWidth() / 5, pageSize.getHeight() / 2.2f);
                canvas.showText(settings.getWatermarkText());
                canvas.endText();
                canvas.restoreState();
            }
        } catch (Exception e) {
            log.warn("Could not draw watermark — skipping: {}", e.getMessage());
        }
    }

    private byte[] downscaleIfNeeded(byte[] original, int maxDimension) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(original));
            if (img == null) {
                return original; // Unsupported format for ImageIO (e.g. WebP) — embed as-is, iText may still handle it.
            }
            if (img.getWidth() <= maxDimension && img.getHeight() <= maxDimension) {
                return original;
            }

            double scale = (double) maxDimension / Math.max(img.getWidth(), img.getHeight());
            int newWidth = Math.max(1, (int) (img.getWidth() * scale));
            int newHeight = Math.max(1, (int) (img.getHeight() * scale));

            BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, newWidth, newHeight, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("Image downscale failed, embedding original bytes: {}", e.getMessage());
            return original;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
