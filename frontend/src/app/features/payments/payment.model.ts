import { PaymentMethod } from '../billing/bill.model';

/** Mirrors com.mtbs.shared.enums.bill.PaymentStatus. */
export type PaymentStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED';

/** Mirrors com.mtbs.business.payment.dto.PaymentResponse. */
export interface Payment {
  id: number;
  invoiceId: number;
  amount: number;
  currency: string;
  method: PaymentMethod;
  /** PENDING for an unconfirmed credit payment; CONFIRMED for collected cash. */
  status: PaymentStatus;
  notes?: string;
  paidAt: string;
  createdAt: string;
}

/** Mirrors com.mtbs.business.payment.dto.CustomerPaymentResponse. */
export interface CustomerPaymentResult {
  paymentGroupId: string;
  totalAmount: number;
  billsCompleted: number;
  payments: Payment[];
}

/** Mirrors com.mtbs.business.payment.dto.CustomerOutstandingResponse.BillOutstandingItem. */
export interface BillOutstandingItem {
  invoiceId: number;
  invoiceNumber: string;
  totalAmount: number;
  outstandingAmount: number;
  createdAt: string;
  dueDate: string | null;
}

/** Mirrors com.mtbs.business.payment.dto.CustomerOutstandingResponse. */
export interface CustomerOutstanding {
  customerId: number;
  totalOutstanding: number;
  bills: BillOutstandingItem[];
}

/** Mirrors com.mtbs.business.payment.dto.RecordCustomerPaymentRequest. */
export interface RecordCustomerPaymentRequest {
  amount: number;
  method: PaymentMethod;
  notes?: string | null;
  paidAt?: string;
}
