package com.mtbs.tenant.settings.dto;

import com.mtbs.shared.enums.settings.BusinessType;
import com.mtbs.shared.enums.settings.PaperSize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Every field is optional — only non-null fields are applied (same partial-
 * update pattern as UpdateCustomerRequest/CustomerService.update).
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdateShopSettingsRequest {

    // ── Business Information ─────────────────────────────────────────────────

    @Size(max = 255)
    private String businessName;

    private Long logoAttachmentId;

    private BusinessType businessType;

    private String address;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Pattern(regexp = "^[0-9]{6}$", message = "Pincode must be a 6-digit number")
    private String pincode;

    @Pattern(regexp = "^[+]?[0-9\\-\\s]{7,15}$", message = "Invalid mobile number")
    private String mobile;

    @Email(message = "Invalid email format")
    private String email;

    @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
             message = "Invalid GSTIN format")
    private String gstin;

    @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]{1}$", message = "Invalid PAN format")
    private String pan;

    @Size(max = 255)
    private String website;

    @Pattern(regexp = "^[\\w.\\-]{2,49}@[\\w]{2,49}$", message = "Invalid UPI ID (expected format: name@bank)")
    private String upiId;

    private Long signatureAttachmentId;

    @Size(max = 50)
    private String watermarkText;

    // ── Invoice & Regional Settings ──────────────────────────────────────────

    @Size(min = 3, max = 3, message = "Currency must be a 3-character ISO code")
    private String currency;

    @Size(max = 5)
    private String currencySymbol;

    @Min(value = 0, message = "Decimal precision must be between 0 and 4")
    @Max(value = 4, message = "Decimal precision must be between 0 and 4")
    private Integer decimalPrecision;

    @Size(max = 50)
    private String timezone;

    @Size(max = 10)
    private String language;

    @Size(max = 20)
    private String dateFormat;

    // ── Bill Settings ─────────────────────────────────────────────────────────

    private PaperSize paperSize;

    private Long billTemplateId;

    private Boolean showLogo;
    private Boolean showGst;
    private Boolean showQrCode;
    private Boolean showCustomerAddress;
    private Boolean showAmountInWords;
    private Boolean showSignature;

    // ── Footer Settings ───────────────────────────────────────────────────────

    private String termsAndConditions;
    private String warrantyText;

    @Size(max = 500)
    private String footerMessage;

    // ── Printer Settings ──────────────────────────────────────────────────────

    @Min(value = 40, message = "Thermal width must be between 40mm and 120mm")
    @Max(value = 120, message = "Thermal width must be between 40mm and 120mm")
    private Integer thermalWidth;

    @Min(value = 0, message = "Margin must be between 0mm and 50mm")
    @Max(value = 50, message = "Margin must be between 0mm and 50mm")
    private Integer margin;

    @Min(value = 6, message = "Font size must be between 6pt and 72pt")
    @Max(value = 72, message = "Font size must be between 6pt and 72pt")
    private Integer fontSize;
}
