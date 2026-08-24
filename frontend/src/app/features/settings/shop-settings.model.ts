export type BusinessType = 'RETAIL' | 'WHOLESALE' | 'SERVICE' | 'RESTAURANT' | 'PHARMACY' | 'OTHER';
export type PaperSize = 'A4' | 'THERMAL_58MM' | 'THERMAL_80MM';

/** Mirrors com.mtbs.tenant.settings.dto.ShopSettingsResponse. */
export interface ShopSettings {
  // Business Information
  businessName?: string;
  logoAttachmentId?: number | null;
  logoUrl?: string | null;
  businessType?: BusinessType;
  address?: string;
  city?: string;
  state?: string;
  pincode?: string;
  mobile?: string;
  email?: string;
  gstin?: string;
  pan?: string;
  website?: string;
  upiId?: string;
  signatureAttachmentId?: number | null;
  signatureUrl?: string | null;
  watermarkText?: string;

  // Bank Details
  bankName?: string;
  bankAccountNo?: string;
  bankIfsc?: string;
  bankBranch?: string;

  // Invoice & Regional Settings
  currency: string;
  currencySymbol: string;
  decimalPrecision: number;
  timezone: string;
  language: string;
  dateFormat: string;

  // Bill Settings
  paperSize: PaperSize;
  billTemplateId: number;
  showLogo: boolean;
  showGst: boolean;
  showQrCode: boolean;
  showCustomerAddress: boolean;
  showAmountInWords: boolean;
  showSignature: boolean;

  // Footer Settings
  termsAndConditions?: string;
  warrantyText?: string;
  footerMessage?: string;

  // Printer Settings
  thermalWidth?: number | null;
  margin: number;
  fontSize: number;
}

/** Mirrors com.mtbs.tenant.settings.dto.UpdateShopSettingsRequest — every field optional (partial update). */
export type UpdateShopSettingsRequest = Partial<ShopSettings>;

/** Mirrors com.mtbs.tenant.billtemplate.dto.BillTemplateResponse. */
export interface BillTemplate {
  id: number;
  code: string;
  name: string;
  description?: string;
}

export type AttachmentPurpose = 'LOGO' | 'SIGNATURE' | 'STAMP' | 'QR_CODE' | 'OTHER';

/** Mirrors com.mtbs.tenant.attachment.dto.AttachmentResponse. */
export interface Attachment {
  id: number;
  purpose: AttachmentPurpose;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  /** Relative API path, e.g. "/api/v1/attachments/5/file" — needs the backend origin prefixed to load as an <img src>. */
  url: string;
  createdAt: string;
}
