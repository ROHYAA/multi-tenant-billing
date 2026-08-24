import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../core/api/api-client';
import { PaymentMethod, RecordPaymentRequest } from '../billing/bill.model';
import { Payment } from './payment.model';

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
}

export type { PaymentMethod };
