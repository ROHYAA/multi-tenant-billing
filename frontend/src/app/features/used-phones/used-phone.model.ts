/** Mirrors UsedPhoneService::toResponse (backend-laravel). */
export interface UsedPhonePurchase {
  id: number;
  voucherNumber: string;
  status: 'IN_STOCK' | 'SOLD';
  productId: number | null;
  sellerName: string;
  sellerMobile: string;
  sellerAddress?: string | null;
  sellerIdType: SellerIdType;
  /** Aadhaar comes back masked: "XXXX XXXX 1234". */
  sellerIdNumber: string;
  idProofUrl?: string | null;
  sellerPhotoUrl?: string | null;
  phonePhotoUrl?: string | null;
  sellerSignatureUrl?: string | null;
  brand: string;
  model: string;
  color?: string | null;
  storage?: string | null;
  imei1: string;
  imei2?: string | null;
  serialNumber?: string | null;
  conditionGrade?: ConditionGrade | null;
  batteryHealth?: number | null;
  accessories: string[];
  hasOriginalBill: boolean;
  ceirChecked: boolean;
  purchasePrice: number;
  sellingPrice: number | null;
  paymentMethod: BuyPaymentMethod;
  notes?: string | null;
  purchasedAt: string;
  soldAt?: string | null;
  soldBillId?: number | null;
  soldInvoiceNumber?: string | null;
  buyerName?: string | null;
  buyerMobile?: string | null;
}

export type SellerIdType = 'AADHAAR' | 'PAN' | 'DRIVING_LICENCE' | 'VOTER_ID' | 'PASSPORT' | 'OTHER';
export type ConditionGrade = 'LIKE_NEW' | 'GOOD' | 'FAIR' | 'POOR';
export type BuyPaymentMethod = 'CASH' | 'UPI' | 'BANK_TRANSFER' | 'CARD';
export type UsedPhonePhotoPurpose = 'ID_PROOF' | 'SELLER_PHOTO' | 'PHONE_PHOTO' | 'SELLER_SIGNATURE';

export interface CreateUsedPhoneRequest {
  sellerName: string;
  sellerMobile: string;
  sellerAddress?: string | null;
  sellerIdType: SellerIdType;
  sellerIdNumber: string;
  idProofAttachmentId: number;
  sellerPhotoAttachmentId?: number | null;
  phonePhotoAttachmentId?: number | null;
  sellerSignatureAttachmentId?: number | null;
  brand: string;
  model: string;
  color?: string | null;
  storage?: string | null;
  imei1: string;
  imei2?: string | null;
  serialNumber?: string | null;
  conditionGrade?: ConditionGrade | null;
  batteryHealth?: number | null;
  accessories?: string[];
  hasOriginalBill?: boolean;
  ceirChecked?: boolean;
  declarationAccepted: boolean;
  purchasePrice: number;
  sellingPrice?: number | null;
  taxPercentage?: number | null;
  paymentMethod?: BuyPaymentMethod;
  notes?: string | null;
}

export interface ImeiHistory {
  imei: string;
  validImei: boolean;
  inStock: boolean;
  purchases: UsedPhonePurchase[];
  sales: {
    billId: number;
    invoiceNumber: string;
    status: string;
    customerName?: string | null;
    date?: string | null;
    amount: number;
  }[];
}

export interface UploadedPhoto {
  id: number;
  url: string;
}

export const ID_TYPE_OPTIONS: { value: SellerIdType; label: string; hint: string }[] = [
  { value: 'AADHAAR', label: 'Aadhaar card', hint: '12 digits — only the last 4 are stored' },
  { value: 'PAN', label: 'PAN card', hint: 'e.g. ABCDE1234F' },
  { value: 'DRIVING_LICENCE', label: 'Driving licence', hint: 'Licence number' },
  { value: 'VOTER_ID', label: 'Voter ID', hint: 'EPIC number' },
  { value: 'PASSPORT', label: 'Passport', hint: 'e.g. A1234567' },
  { value: 'OTHER', label: 'Other ID', hint: 'ID number' },
];

export const CONDITION_OPTIONS: { value: ConditionGrade; label: string }[] = [
  { value: 'LIKE_NEW', label: 'Like new' },
  { value: 'GOOD', label: 'Good' },
  { value: 'FAIR', label: 'Fair' },
  { value: 'POOR', label: 'Poor' },
];

export const ACCESSORY_OPTIONS = ['Charger', 'Box', 'Cable', 'Earphones', 'Cover'];

/** 15 digits with a valid Luhn check digit — what *#06# shows on the phone. */
export function isValidImei(value: string): boolean {
  const digits = value.replace(/[\s-]/g, '');
  if (!/^\d{15}$/.test(digits)) return false;
  let sum = 0;
  for (let i = 0; i < 15; i++) {
    let d = Number(digits[14 - i]);
    if (i % 2 === 1) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
  }
  return sum % 10 === 0;
}
