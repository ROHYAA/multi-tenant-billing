import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, QueryParams } from '../../core/api/api-client';
import { PageResponse } from '../../core/models/api-response.model';
import { Bill, CreateBillRequest, Product, RecordPaymentRequest } from './bill.model';

@Injectable({ providedIn: 'root' })
export class BillService {
  private readonly api = inject(ApiClient);

  /** Unpaginated — all active products, prefetched once for instant client-side search. */
  listActiveProducts(): Observable<Product[]> {
    return this.api.get<Product[]>('/products/active');
  }

  list(params: QueryParams): Observable<PageResponse<Bill>> {
    return this.api.get<PageResponse<Bill>>('/business-invoices', params);
  }

  create(request: CreateBillRequest): Observable<Bill> {
    return this.api.post<Bill>('/business-invoices', request);
  }

  finalize(billId: number): Observable<Bill> {
    return this.api.post<Bill>(`/business-invoices/${billId}/finalize`, {});
  }

  recordPayment(billId: number, request: RecordPaymentRequest): Observable<unknown> {
    return this.api.post(`/business-payments/${billId}`, request);
  }

  downloadPdf(billId: number, copyType?: 'ORIGINAL' | 'DUPLICATE' | 'TRIPLICATE'): Observable<Blob> {
    return this.api.getBlob(`/business-invoices/${billId}/download`, copyType ? { copyType } : undefined);
  }
}
