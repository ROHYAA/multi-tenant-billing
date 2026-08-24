import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Sort } from '@angular/material/sort';
import { MatTooltipModule } from '@angular/material/tooltip';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { ApiError, PageResponse } from '../../../core/models/api-response.model';
import { DataTable, DataTableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Bill } from '../bill.model';
import { BillService } from '../bill.service';

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'All statuses' },
  { value: 'DRAFT', label: 'Draft' },
  { value: 'OPEN', label: 'Open' },
  { value: 'PAID', label: 'Paid' },
  { value: 'VOID', label: 'Void' },
  { value: 'UNCOLLECTIBLE', label: 'Uncollectible' },
];

const STATUS_BADGE_CLASS: Record<string, string> = {
  DRAFT: 'bg-[var(--mat-sys-surface-container-highest)] text-[var(--mat-sys-on-surface-variant)]',
  OPEN: 'bg-[var(--mat-sys-secondary-container)] text-[var(--mat-sys-on-secondary-container)]',
  PAID: 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]',
  VOID: 'bg-[var(--mat-sys-error-container)] text-[var(--mat-sys-on-error-container)]',
  UNCOLLECTIBLE: 'bg-[var(--mat-sys-error-container)] text-[var(--mat-sys-on-error-container)]',
};

@Component({
  selector: 'app-bill-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatMenuModule,
    MatSelectModule,
    MatTooltipModule,
    DataTable,
    PageHeader,
  ],
  templateUrl: './bill-list.html',
})
export class BillList {
  private readonly billService = inject(BillService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly statusOptions = STATUS_OPTIONS;
  protected readonly statusControl = new FormControl('', { nonNullable: true });

  protected readonly page = signal<PageResponse<Bill> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly reprintingId = signal<number | null>(null);

  private pageIndex = 0;
  private readonly pageSize = 20;
  private sort: Sort | null = null;

  protected readonly columns: DataTableColumn<Bill>[] = [
    { key: 'invoiceNumber', header: 'Bill No.', sortable: true, cell: (b) => b.invoiceNumber },
    { key: 'customerName', header: 'Customer', cell: (b) => b.customerName },
    {
      key: 'status',
      header: 'Status',
      type: 'badge',
      cell: (b) => b.status,
      badgeClass: (b) => STATUS_BADGE_CLASS[b.status] ?? '',
    },
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

    this.statusControl.valueChanges
      .pipe(debounceTime(0), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.pageIndex = 0;
        this.load();
      });
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    const params: Record<string, string | number> = { page: this.pageIndex, size: this.pageSize };
    if (this.statusControl.value) params['status'] = this.statusControl.value;
    if (this.sort?.direction) params['sort'] = `${this.sort.active},${this.sort.direction}`;

    this.billService.list(params).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load bills.');
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

  reprint(bill: Bill): void {
    this.reprintingId.set(bill.id);
    this.billService.downloadPdf(bill.id, 'DUPLICATE').subscribe({
      next: (blob) => {
        this.reprintingId.set(null);
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
      },
      error: (err: ApiError) => {
        this.reprintingId.set(null);
        this.snackBar.open(err.message || 'Failed to generate PDF.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  retry(): void {
    this.load();
  }

  private formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(value);
  }
}
