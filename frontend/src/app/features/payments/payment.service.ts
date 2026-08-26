import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../core/api/api-client';
import { PaymentMethod, RecordPaymentRequest } from '../billing/bill.model';
import { CustomerOutstanding, CustomerPaymentResult, Payment, RecordCustomerPaymentRequest } from './payment.model';

@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly api = inject(ApiClient);

  listForInvoice(invoiceId: number): Observable<Payment[]> {
    return this.api.get<Payment[]>(`/business-payments/invoice/${invoiceId}`);
  }

  getOutstandingBalance(invoiceId: number): Observable<number> {
    return this.api.get<number>(`/business-payments/invoice/${invoiceId}/outstanding`);
  }

  record(invoiceId: number, request: RecordPaymentRequest): Observable<Payment> {
    return this.api.post<Payment>(`/business-payments/${invoiceId}`, request);
  }

  /** Records one customer payment, allocated FIFO (oldest bill first) across their OPEN bills. */
  recordForCustomer(customerId: number, request: RecordCustomerPaymentRequest): Observable<CustomerPaymentResult> {
    return this.api.post<CustomerPaymentResult>(`/business-payments/customer/${customerId}`, request);
  }

  /** Total outstanding + the ordered per-bill breakdown a FIFO payment would apply against. */
  getCustomerOutstanding(customerId: number): Observable<CustomerOutstanding> {
    return this.api.get<CustomerOutstanding>(`/business-payments/customer/${customerId}/outstanding`);
  }

  /** Flips a PENDING credit payment to CONFIRMED — settles the bill if this completes it. */
  confirmPayment(paymentId: number): Observable<Payment> {
    return this.api.patch<Payment>(`/business-payments/${paymentId}/confirm`, {});
  }
}

export type { PaymentMethod };
