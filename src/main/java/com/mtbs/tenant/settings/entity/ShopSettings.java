package com.mtbs.tenant.settings.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.settings.BusinessType;
import com.mtbs.shared.enums.settings.PaperSize;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Singleton configuration row for the current shop — exactly one row per
 * tenant schema, seeded by V22__create_shop_settings.sql at provisioning
 * time, never null-checked/lazily-created in application code.
 *
 * No @SQLDelete/delete endpoint — settings are edited, never removed.
 */
@Entity
@Table(name = "shop_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopSettings extends AuditableEntity {

    // ── Business Information ─────────────────────────────────────────────────

    @Column(name = "business_name", length = 255)
    private String businessName;

    /** References attachment.Attachment.id in this same tenant schema. */
    @Column(name = "logo_attachment_id")
    private Long logoAttachmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", length = 30)
    private BusinessType businessType;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 10)
    private String pincode;

    @Column(length = 20)
    private String mobile;

    @Column(length = 255)
    private String email;

    @Column(length = 15)
    private String gstin;

    @Column(length = 10)
    private String pan;

    @Column(length = 255)
    private String website;

    /** UPI VPA (e.g. "shopname@upi") — encoded into the dynamic UPI QR when set. */
    @Column(name = "upi_id", length = 100)
    private String upiId;

    /** References attachment.Attachment.id — falls back to a text signature line when null. */
    @Column(name = "signature_attachment_id")
    private Long signatureAttachmentId;

    /** Diagonal watermark text (e.g. "COPY", "DRAFT") — no watermark drawn when null/blank. */
    @Column(name = "watermark_text", length = 50)
    private String watermarkText;

    // ── Bank Details ──────────────────────────────────────────────────────────

    @Column(name = "bank_name", length = 255)
    private String bankName;

    @Column(name = "bank_account_no", length = 30)
    private String bankAccountNo;

    @Column(name = "bank_ifsc", length = 11)
    private String bankIfsc;

    @Column(name = "bank_branch", length = 255)
    private String bankBranch;

    // ── Invoice & Regional Settings ──────────────────────────────────────────

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "currency_symbol", nullable = false, length = 5)
    @Builder.Default
    private String currencySymbol = "₹";

    @Column(name = "decimal_precision", nullable = false)
    @Builder.Default
    private Integer decimalPrecision = 2;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String timezone = "Asia/Kolkata";

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String language = "en-IN";

    @Column(name = "date_format", nullable = false, length = 20)
    @Builder.Default
    private String dateFormat = "dd/MM/yyyy";

    // ── Bill Settings ─────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "paper_size", nullable = false, length = 20)
    @Builder.Default
    private PaperSize paperSize = PaperSize.A4;

    /** References billtemplate.BillTemplate.id in the public schema (no enforced cross-schema FK — see V22 migration). */
    @Column(name = "bill_template_id", nullable = false)
    private Long billTemplateId;

    @Column(name = "show_logo", nullable = false)
    @Builder.Default
    private Boolean showLogo = true;

    @Column(name = "show_gst", nullable = false)
    @Builder.Default
    private Boolean showGst = true;

    @Column(name = "show_qr_code", nullable = false)
    @Builder.Default
    private Boolean showQrCode = false;

    @Column(name = "show_customer_address", nullable = false)
    @Builder.Default
    private Boolean showCustomerAddress = true;

    @Column(name = "show_amount_in_words", nullable = false)
    @Builder.Default
    private Boolean showAmountInWords = true;

    @Column(name = "show_signature", nullable = false)
    @Builder.Default
    private Boolean showSignature = false;

    // ── Footer Settings ───────────────────────────────────────────────────────

    @Column(name = "terms_and_conditions", columnDefinition = "TEXT")
    private String termsAndConditions;

    @Column(name = "warranty_text", columnDefinition = "TEXT")
    private String warrantyText;

    @Column(name = "footer_message", length = 500)
    private String footerMessage;

    // ── Printer Settings ──────────────────────────────────────────────────────

    @Column(name = "thermal_width")
    private Integer thermalWidth;

    @Column(nullable = false)
    @Builder.Default
    private Integer margin = 5;

    @Column(name = "font_size", nullable = false)
    @Builder.Default
    private Integer fontSize = 10;
}
