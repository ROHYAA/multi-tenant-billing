import { PaymentMethod } from '../billing/bill.model';

/** Mirrors com.mtbs.business.payment.dto.PaymentResponse. */
export interface Payment {
  id: number;
  invoiceId: number;
  amount: number;
  currency: string;
  method: PaymentMethod;
  notes?: string;
  paidAt: string;
  createdAt: string;
}
