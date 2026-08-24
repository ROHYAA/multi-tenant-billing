package com.mtbs.tenant.settings.service;

import com.mtbs.shared.enums.settings.PaperSize;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.attachment.service.AttachmentService;
import com.mtbs.tenant.billtemplate.service.BillTemplateService;
import com.mtbs.tenant.settings.dto.ShopSettingsResponse;
import com.mtbs.tenant.settings.dto.UpdateShopSettingsRequest;
import com.mtbs.tenant.settings.entity.ShopSettings;
import com.mtbs.tenant.settings.repository.ShopSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads/updates the current shop's single settings row. There is always
 * exactly one row post-migration (see V22__create_shop_settings.sql) —
 * this service never lazily creates or null-checks it into existence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopSettingsService {

    private final ShopSettingsRepository shopSettingsRepository;
    private final AttachmentService attachmentService;
    private final BillTemplateService billTemplateService;

    @Transactional(readOnly = true)
    public ShopSettingsResponse getSettings() {
        return toResponse(getEntity());
    }

    /** Used by BillPdfService/BillService to read settings directly. */
    @Transactional(readOnly = true)
    public ShopSettings getEntity() {
        return shopSettingsRepository.findTopByOrderByIdAsc()
                .orElseThrow(() -> ResourceException.notFound("ShopSettings", "singleton row"));
    }

    @Transactional
    public ShopSettingsResponse updateSettings(UpdateShopSettingsRequest request) {
        ShopSettings settings = getEntity();

        applyBusinessInformation(settings, request);
        applyBankDetails(settings, request);
        applyInvoiceAndRegionalSettings(settings, request);
        applyBillSettings(settings, request);
        applyFooterSettings(settings, request);
        applyPrinterSettings(settings, request);

        validateCrossFieldRules(settings);

        ShopSettings saved = shopSettingsRepository.save(settings);
        log.info("Shop settings updated");
        return toResponse(saved);
    }

    // ── Field application (partial update — only non-null fields applied) ────

    private void applyBusinessInformation(ShopSettings settings, UpdateShopSettingsRequest r) {
        if (r.getBusinessName() != null) settings.setBusinessName(r.getBusinessName());
        if (r.getLogoAttachmentId() != null) {
            attachmentService.getById(r.getLogoAttachmentId()); // throws if not found
            settings.setLogoAttachmentId(r.getLogoAttachmentId());
        }
        if (r.getBusinessType() != null) settings.setBusinessType(r.getBusinessType());
        if (r.getAddress()     != null) settings.setAddress(r.getAddress());
        if (r.getCity()        != null) settings.setCity(r.getCity());
        if (r.getState()       != null) settings.setState(r.getState());
        if (r.getPincode()     != null) settings.setPincode(r.getPincode());
        if (r.getMobile()      != null) settings.setMobile(r.getMobile());
        if (r.getEmail()       != null) settings.setEmail(r.getEmail());
        if (r.getGstin()       != null) settings.setGstin(r.getGstin());
        if (r.getPan()         != null) settings.setPan(r.getPan());
        if (r.getWebsite()     != null) settings.setWebsite(r.getWebsite());
        if (r.getUpiId()       != null) settings.setUpiId(r.getUpiId());
        if (r.getWatermarkText() != null) settings.setWatermarkText(r.getWatermarkText());
        if (r.getSignatureAttachmentId() != null) {
            attachmentService.getById(r.getSignatureAttachmentId()); // throws if not found
            settings.setSignatureAttachmentId(r.getSignatureAttachmentId());
        }
    }

    private void applyBankDetails(ShopSettings settings, UpdateShopSettingsRequest r) {
        if (r.getBankName()       != null) settings.setBankName(r.getBankName());
        if (r.getBankAccountNo()  != null) settings.setBankAccountNo(r.getBankAccountNo());
        if (r.getBankIfsc()       != null) settings.setBankIfsc(r.getBankIfsc());
        if (r.getBankBranch()     != null) settings.setBankBranch(r.getBankBranch());
    }

    private void applyInvoiceAndRegionalSettings(ShopSettings settings, UpdateShopSettingsRequest r) {
        if (r.getCurrency()         != null) settings.setCurrency(r.getCurrency());
        if (r.getCurrencySymbol()   != null) settings.setCurrencySymbol(r.getCurrencySymbol());
        if (r.getDecimalPrecision() != null) settings.setDecimalPrecision(r.getDecimalPrecision());
        if (r.getTimezone()         != null) settings.setTimezone(r.getTimezone());
        if (r.getLanguage()         != null) settings.setLanguage(r.getLanguage());
        if (r.getDateFormat()       != null) settings.setDateFormat(r.getDateFormat());
    }

    private void applyBillSettings(ShopSettings settings, UpdateShopSettingsRequest r) {
        if (r.getPaperSize() != null) settings.setPaperSize(r.getPaperSize());
        if (r.getBillTemplateId() != null) {
            billTemplateService.getEntityById(r.getBillTemplateId()); // throws if not found
            settings.setBillTemplateId(r.getBillTemplateId());
        }
        if (r.getShowLogo()             != null) settings.setShowLogo(r.getShowLogo());
        if (r.getShowGst()              != null) settings.setShowGst(r.getShowGst());
        if (r.getShowQrCode()           != null) settings.setShowQrCode(r.getShowQrCode());
        if (r.getShowCustomerAddress()  != null) settings.setShowCustomerAddress(r.getShowCustomerAddress());
        if (r.getShowAmountInWords()    != null) settings.setShowAmountInWords(r.getShowAmountInWords());
        if (r.getShowSignature()        != null) settings.setShowSignature(r.getShowSignature());
    }

    private void applyFooterSettings(ShopSettings settings, UpdateShopSettingsRequest r) {
        if (r.getTermsAndConditions() != null) settings.setTermsAndConditions(r.getTermsAndConditions());
        if (r.getWarrantyText()       != null) settings.setWarrantyText(r.getWarrantyText());
        if (r.getFooterMessage()      != null) settings.setFooterMessage(r.getFooterMessage());
    }

    private void applyPrinterSettings(ShopSettings settings, UpdateShopSettingsRequest r) {
        if (r.getThermalWidth() != null) settings.setThermalWidth(r.getThermalWidth());
        if (r.getMargin()       != null) settings.setMargin(r.getMargin());
        if (r.getFontSize()     != null) settings.setFontSize(r.getFontSize());
    }

    /**
     * Cross-field rules not expressible via bean validation on the request
     * alone (they depend on the combination of two fields, and either one
     * may be unchanged by this particular request).
     */
    private void validateCrossFieldRules(ShopSettings settings) {
        Integer thermalWidth = settings.getThermalWidth();
        if (thermalWidth == null) {
            return;
        }
        PaperSize paperSize = settings.getPaperSize();
        boolean mismatch =
                (paperSize == PaperSize.THERMAL_58MM && thermalWidth != 58) ||
                (paperSize == PaperSize.THERMAL_80MM && thermalWidth != 80) ||
                (paperSize == PaperSize.A4 && thermalWidth != null);
        if (mismatch) {
            throw ResourceException.invalid(
                    "thermalWidth (" + thermalWidth + "mm) does not match paperSize (" + paperSize + ")");
        }
    }

    private ShopSettingsResponse toResponse(ShopSettings settings) {
        String logoUrl = settings.getLogoAttachmentId() != null
                ? attachmentService.getById(settings.getLogoAttachmentId()).getUrl()
                : null;
        String signatureUrl = settings.getSignatureAttachmentId() != null
                ? attachmentService.getById(settings.getSignatureAttachmentId()).getUrl()
                : null;

        return ShopSettingsResponse.builder()
                .businessName(settings.getBusinessName())
                .logoAttachmentId(settings.getLogoAttachmentId())
                .logoUrl(logoUrl)
                .businessType(settings.getBusinessType())
                .address(settings.getAddress())
                .city(settings.getCity())
                .state(settings.getState())
                .pincode(settings.getPincode())
                .mobile(settings.getMobile())
                .email(settings.getEmail())
                .gstin(settings.getGstin())
                .pan(settings.getPan())
                .website(settings.getWebsite())
                .upiId(settings.getUpiId())
                .signatureAttachmentId(settings.getSignatureAttachmentId())
                .signatureUrl(signatureUrl)
                .watermarkText(settings.getWatermarkText())
                .bankName(settings.getBankName())
                .bankAccountNo(settings.getBankAccountNo())
                .bankIfsc(settings.getBankIfsc())
                .bankBranch(settings.getBankBranch())
                .currency(settings.getCurrency())
                .currencySymbol(settings.getCurrencySymbol())
                .decimalPrecision(settings.getDecimalPrecision())
                .timezone(settings.getTimezone())
                .language(settings.getLanguage())
                .dateFormat(settings.getDateFormat())
                .paperSize(settings.getPaperSize())
                .billTemplateId(settings.getBillTemplateId())
                .showLogo(settings.getShowLogo())
                .showGst(settings.getShowGst())
                .showQrCode(settings.getShowQrCode())
                .showCustomerAddress(settings.getShowCustomerAddress())
                .showAmountInWords(settings.getShowAmountInWords())
                .showSignature(settings.getShowSignature())
                .termsAndConditions(settings.getTermsAndConditions())
                .warrantyText(settings.getWarrantyText())
                .footerMessage(settings.getFooterMessage())
                .thermalWidth(settings.getThermalWidth())
                .margin(settings.getMargin())
                .fontSize(settings.getFontSize())
                .build();
    }
}
