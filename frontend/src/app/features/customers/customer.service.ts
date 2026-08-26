import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map, of } from 'rxjs';
import { ApiClient, QueryParams } from '../../core/api/api-client';
import { PageResponse } from '../../core/models/api-response.model';
import { AuthService } from '../../core/auth/auth';
import { CustomerOutstanding } from '../payments/payment.model';
import {
  CreateCustomerRequest,
  Customer,
  CustomerFinancialSummary,
  UpdateCustomerRequest,
} from './customer.model';

/** Minimal slice of BillResponse this service actually reads — see com.mtbs.business.invoice.dto.BillResponse. */
interface BillSummaryRow {
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class CustomerService {
  private readonly api = inject(ApiClient);
  private readonly authService = inject(AuthService);

  list(params: QueryParams): Observable<PageResponse<Customer>> {
    return this.api.get<PageResponse<Customer>>('/customers', params);
  }

  getById(id: number): Observable<Customer> {
    return this.api.get<Customer>(`/customers/${id}`);
  }

  create(request: CreateCustomerRequest): Observable<Customer> {
    return this.api.post<Customer>('/customers', request);
  }

  update(id: number, request: UpdateCustomerRequest): Observable<Customer> {
    return this.api.put<Customer>(`/customers/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.api.delete<void>(`/customers/${id}`);
  }

  /** This shop's system-seeded Walk-in Customer — default selection for cash/walk-in sales. */
  getWalkIn(): Observable<Customer> {
    return this.api.get<Customer>('/customers/walkin');
  }

  /**
   * CustomerResponse carries no financial fields, so Customer Details composes
   * this from two other endpoints rather than a new combined backend
   * aggregate — both gated on BILLING_MANAGE (a separate permission from
   * CUSTOMER_MANAGE), so a user without it simply gets nulls back instead of
   * a 403 bubbling up.
   *
   * - totalBills / lastTransactionAt: one GET /business-invoices?customerId=
   *   call (size=1, sort=createdAt,desc) — totalElements + the newest row.
   * - outstandingAmount: GET /business-payments/customer/{id}/outstanding —
   *   a real per-customer aggregate (added alongside FIFO payments), CONFIRMED-
   *   only. Replaces the old workaround of fetching every OPEN invoice
   *   shop-wide from /reports/outstanding and filtering client-side.
   */
  getFinancialSummary(customerId: number): Observable<CustomerFinancialSummary> {
    if (!this.authService.currentUser()?.permissions.includes('BILLING_MANAGE')) {
      return of({ totalBills: null, lastTransactionAt: null, outstandingAmount: null });
    }

    const lastBill$ = this.api.get<PageResponse<BillSummaryRow>>('/business-invoices', {
      customerId,
      size: 1,
      sort: 'createdAt,desc',
    });
    const outstanding$ = this.api.get<CustomerOutstanding>(`/business-payments/customer/${customerId}/outstanding`);

    return forkJoin([lastBill$, outstanding$]).pipe(
      map(([billsPage, outstanding]) => ({
        totalBills: billsPage.totalElements,
        lastTransactionAt: billsPage.content[0]?.createdAt ?? null,
        outstandingAmount: outstanding.totalOutstanding,
      })),
    );
  }
}
