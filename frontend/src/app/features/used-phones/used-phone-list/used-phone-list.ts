import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { ApiError, PageResponse } from '../../../core/models/api-response.model';
import { DataTable, DataTableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { UsedPhonePurchase } from '../used-phone.model';
import { UsedPhoneService } from '../used-phone.service';
import { printPdf } from '../../../shared/utils/file-output';

type StatusFilter = 'ALL' | 'IN_STOCK' | 'SOLD';

@Component({
  selector: 'app-used-phone-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    DataTable,
    PageHeader,
  ],
  templateUrl: './used-phone-list.html',
})
export class UsedPhoneList {
  private readonly usedPhones = inject(UsedPhoneService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly searchControl = new FormControl('', { nonNullable: true });
  protected readonly status = signal<StatusFilter>('ALL');
  protected readonly page = signal<PageResponse<UsedPhonePurchase> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  private pageIndex = 0;
  private readonly pageSize = 20;

  protected readonly columns: DataTableColumn<UsedPhonePurchase>[] = [
    { key: 'voucherNumber', header: 'Voucher', cell: (p) => p.voucherNumber },
    { key: 'purchasedAt', header: 'Bought on', cell: (p) => this.formatDate(p.purchasedAt) },
    { key: 'device', header: 'Phone', cell: (p) => [p.brand, p.model, p.storage].filter(Boolean).join(' ') },
    { key: 'imei1', header: 'IMEI', cell: (p) => p.imei1 },
    { key: 'seller', header: 'Seller', cell: (p) => `${p.sellerName} · ${p.sellerMobile}` },
    { key: 'purchasePrice', header: 'Paid', align: 'right', cell: (p) => this.formatCurrency(p.purchasePrice) },
    {
      key: 'status',
      header: 'Status',
      type: 'badge',
      cell: (p) => (p.status === 'SOLD' ? `Sold${p.soldInvoiceNumber ? ' · ' + p.soldInvoiceNumber : ''}` : 'In stock'),
      badgeClass: (p) =>
        p.status === 'SOLD'
          ? 'bg-[var(--mat-sys-surface-container-highest)] text-[var(--mat-sys-on-surface-variant)]'
          : 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]',
    },
  ];

  constructor() {
    this.load();
    this.searchControl.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.pageIndex = 0;
        this.load();
      });
  }

  setStatus(value: StatusFilter): void {
    this.status.set(value);
    this.pageIndex = 0;
    this.load();
  }

  onPageChange(pageIndex: number): void {
    this.pageIndex = pageIndex;
    this.load();
  }

  openVoucher(purchase: UsedPhonePurchase): void {
    this.usedPhones.voucherPdf(purchase.id).subscribe({
      next: (blob) =>
        printPdf(blob, `${purchase.voucherNumber}.pdf`).catch((err: unknown) =>
          this.snackBar.open((err as Error)?.message || 'Could not open the voucher.', 'Dismiss', { duration: 6000 }),
        ),
      error: (err: ApiError) => this.snackBar.open(err.message || 'Failed to open voucher.', 'Dismiss', { duration: 6000 }),
    });
  }

  retry(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    const params: Record<string, string | number> = { page: this.pageIndex, size: this.pageSize };
    const search = this.searchControl.value.trim();
    if (search) params['search'] = search;
    if (this.status() !== 'ALL') params['status'] = this.status();

    this.usedPhones.list(params).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load used phones.');
      },
    });
  }

  private formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(value);
  }

  private formatDate(value: string): string {
    return new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(value));
  }
}
