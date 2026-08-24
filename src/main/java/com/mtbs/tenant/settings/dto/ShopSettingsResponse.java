package com.mtbs.tenant.settings.dto;

import com.mtbs.shared.enums.settings.BusinessType;
import com.mtbs.shared.enums.settings.PaperSize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ShopSettingsResponse {

    // Business Information
    private String businessName;
    private Long logoAttachmentId;
    /** Computed from logoAttachmentId via AttachmentService — null if no logo uploaded. */
    private String logoUrl;
    private BusinessType businessType;
    private String address;
    private String city;
    private String state;
    private String pincode;
    private String mobile;
    private String email;
    private String gstin;
    private String pan;
    private String website;
    private String upiId;
    private Long signatureAttachmentId;
    /** Computed from signatureAttachmentId via AttachmentService — null if no signature image uploaded. */
    private String signatureUrl;
    private String watermarkText;

    // Bank Details
    private String bankName;
    private String bankAccountNo;
    private String bankIfsc;
    private String bankBranch;

    // Invoice & Regional Settings
    private String currency;
    private String currencySymbol;
    private Integer decimalPrecision;
    private String timezone;
    private String language;
    private String dateFormat;

    // Bill Settings
    private PaperSize paperSize;
    private Long billTemplateId;
    private Boolean showLogo;
    private Boolean showGst;
    private Boolean showQrCode;
    private Boolean showCustomerAddress;
    private Boolean showAmountInWords;
    private Boolean showSignature;

    // Footer Settings
    private String termsAndConditions;
    private String warrantyText;
    private String footerMessage;

    // Printer Settings
    private Integer thermalWidth;
    private Integer margin;
    private Integer fontSize;
}
