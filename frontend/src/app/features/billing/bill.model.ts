/** Minimal slice of com.mtbs.business.product.dto.ProductResponse this feature reads. */
export interface Product {
  id: number;
  name: string;
  price: number;
  taxPercentage: number;
  isActive: boolean;
}

/**
 * UI-only cart line — nothing here is persisted until Save. CGST/SGST are
 * tracked as separate editable rates (standard intra-state GST split) rather
 * than one combined "tax %" field — the backend still only stores one
 * combined taxPercentage per line, computed as cgstPercentage + sgstPercentage
 * when the bill is submitted.
 */
export interface CartLine {
  localId: string;
  productId: number | null;
  description: string;
  quantity: number;
  unitPrice: number;
  cgstPercentage: number;
  sgstPercentage: number;
}

/** Matches backend PaymentMethod enum exactly (CASH, CARD, UPI, NETBANKING, BANK_TRANSFER, CREDIT). */
export type PaymentMethod = 'CASH' | 'CARD' | 'UPI' | 'NETBANKING' | 'BANK_TRANSFER' | 'CREDIT';

/** Mirrors com.mtbs.business.invoice.dto.CreateBillRequest. */
export interface CreateBillRequest {
  customerId: number;
  notes?: string | null;
  discountAmount?: number;
  items: {
    productId?: number | null;
    description?: string;
    quantity: number;
    unitPrice?: number;
    taxPercentage?: number;
  }[];
}

/** Minimal slice of com.mtbs.business.invoice.dto.BillResponse this feature reads. */
export interface Bill {
  id: number;
  invoiceNumber: string;
  customerId: number;
  customerName: string;
  status: 'DRAFT' | 'OPEN' | 'PAID' | 'VOID' | 'UNCOLLECTIBLE';
  subtotal: number;
  taxAmount: number;
  discountAmount: number;
  totalAmount: number;
  currency: string;
  createdAt: string;
}

/** Mirrors com.mtbs.business.payment.dto.RecordPaymentRequest. */
export interface RecordPaymentRequest {
  amount: number;
  method: PaymentMethod;
  notes?: string | null;
  /** ISO-8601 instant. Optional — defaults to now server-side; set to backdate an offline payment. */
  paidAt?: string;
}
