<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\ShopSetting;

class ShopSettingsService
{
    public function __construct(
        private BusinessTypeCatalog $businessTypes,
        private AttachmentService $attachments,
    ) {}

    private const MAP = [
        'businessName' => 'business_name',
        'logoAttachmentId' => 'logo_attachment_id',
        'businessType' => 'business_type',
        'address' => 'address',
        'city' => 'city',
        'state' => 'state',
        'pincode' => 'pincode',
        'mobile' => 'mobile',
        'email' => 'email',
        'gstin' => 'gstin',
        'pan' => 'pan',
        'website' => 'website',
        'upiId' => 'upi_id',
        'signatureAttachmentId' => 'signature_attachment_id',
        'watermarkText' => 'watermark_text',
        'bankName' => 'bank_name',
        'bankAccountNo' => 'bank_account_no',
        'bankIfsc' => 'bank_ifsc',
        'bankBranch' => 'bank_branch',
        'currency' => 'currency',
        'currencySymbol' => 'currency_symbol',
        'decimalPrecision' => 'decimal_precision',
        'timezone' => 'timezone',
        'language' => 'language',
        'dateFormat' => 'date_format',
        'paperSize' => 'paper_size',
        'billTemplateId' => 'bill_template_id',
        'showLogo' => 'show_logo',
        'showGst' => 'show_gst',
        'showQrCode' => 'show_qr_code',
        'showCustomerAddress' => 'show_customer_address',
        'showAmountInWords' => 'show_amount_in_words',
        'showSignature' => 'show_signature',
        'termsAndConditions' => 'terms_and_conditions',
        'warrantyText' => 'warranty_text',
        'footerMessage' => 'footer_message',
        'thermalWidth' => 'thermal_width',
        'margin' => 'margin',
        'fontSize' => 'font_size',
    ];

    public function get(): array
    {
        return $this->toResponse($this->row());
    }

    public function update(array $data): array
    {
        if (array_key_exists('businessType', $data)) {
            $this->businessTypes->assertActive($data['businessType']);
        }
        $row = $this->row();
        foreach (self::MAP as $in => $col) {
            if (array_key_exists($in, $data)) {
                $row->{$col} = $data[$in];
            }
        }
        $row->save();

        return $this->toResponse($row);
    }

    private function row(): ShopSetting
    {
        $row = ShopSetting::query()->first();
        if (! $row) {
            throw ApiException::notFound('ShopSettings', 'current');
        }

        return $row;
    }

    private function toResponse(ShopSetting $row): array
    {
        $out = [];
        foreach (self::MAP as $camel => $col) {
            $out[$camel] = $row->{$col};
        }
        $out['logoUrl'] = $this->attachments->url($row->logo_attachment_id ? (int) $row->logo_attachment_id : null);
        $out['signatureUrl'] = $this->attachments->url($row->signature_attachment_id ? (int) $row->signature_attachment_id : null);
        $out['showLogo'] = (bool) $row->show_logo;
        $out['showGst'] = (bool) $row->show_gst;
        $out['showQrCode'] = (bool) $row->show_qr_code;
        $out['showCustomerAddress'] = (bool) $row->show_customer_address;
        $out['showAmountInWords'] = (bool) $row->show_amount_in_words;
        $out['showSignature'] = (bool) $row->show_signature;
        $out['decimalPrecision'] = (int) $row->decimal_precision;
        $out['thermalWidth'] = $row->thermal_width !== null ? (int) $row->thermal_width : null;
        $out['margin'] = $row->margin !== null ? (int) $row->margin : null;
        $out['fontSize'] = $row->font_size !== null ? (int) $row->font_size : null;
        $out['billTemplateId'] = $row->bill_template_id ? (int) $row->bill_template_id : null;
        $out['logoAttachmentId'] = $row->logo_attachment_id ? (int) $row->logo_attachment_id : null;
        $out['signatureAttachmentId'] = $row->signature_attachment_id ? (int) $row->signature_attachment_id : null;

        return $out;
    }
}
