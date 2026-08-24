import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Sort } from '@angular/material/sort';
import { ApiError, PageResponse } from '../../../core/models/api-response.model';
import { Bill } from '../../billing/bill.model';
import { BillService } from '../../billing/bill.service';
import { DataTable, DataTableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Payment } from '../payment.model';
import { RecordPaymentDialog } from '../record-payment-dialog/record-payment-dialog';

/**
 * Collections queue — OPEN bills still owed, with a Record Payment action.
 * Separate from the general Bill List (which shows every status): this page
 * is specifically "what still needs money collected," the actionable view
 * for a shopkeeper following up on unpaid bills after the point of sale.
 */
@Component({
  selector: 'app-payments-page',
  imports: [MatButtonModule, MatIconModule, DataTable, PageHeader],
  templateUrl: './payments-page.html',
})
export class PaymentsPage {
  private readonly billService = inject(BillService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly page = signal<PageResponse<Bill> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  private pageIndex = 0;
  private readonly pageSize = 20;
  private sort: Sort | null = null;

  protected readonly columns: DataTableColumn<Bill>[] = [
    { key: 'invoiceNumber', header: 'Bill No.', sortable: true, cell: (b) => b.invoiceNumber },
    { key: 'customerName', header: 'Customer', cell: (b) => b.customerName },
    { key: 'totalAmount', header: 'Total', align: 'right', cell: (b) => this.formatCurrency(b.totalAmount) },
    {
      key: 'createdAt',
      header: 'Date',
      sortable: true,
      align: 'right',
      cell: (b) => new Date(b.createdAt).toLocaleDateString(),
    },
  ];

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    const params: Record<string, string | number> = { page: this.pageIndex, size: this.pageSize, status: 'OPEN' };
    if (this.sort?.direction) params['sort'] = `${this.sort.active},${this.sort.direction}`;

    this.billService.list(params).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load unpaid bills.');
      },
    });
  }

  onPageChange(pageIndex: number): void {
    this.pageIndex = pageIndex;
    this.load();
  }

  onSortChange(sort: Sort): void {
    this.sort = sort.direction ? sort : null;
    this.pageIndex = 0;
    this.load();
  }

  recordPayment(bill: Bill): void {
    this.dialog
      .open(RecordPaymentDialog, { width: '440px', data: { invoiceId: bill.id, invoiceNumber: bill.invoiceNumber } })
      .afterClosed()
      .subscribe((payment: Payment | undefined) => {
        if (payment) {
          this.snackBar.open('Payment recorded successfully', 'Dismiss', { duration: 4000 });
          this.load();
        }
      });
  }

  retry(): void {
    this.load();
  }

  private formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(value);
  }
}
