import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Sort } from '@angular/material/sort';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { ApiError, PageResponse } from '../../../core/models/api-response.model';
import { ConfirmDialogService } from '../../../shared/components/confirm-dialog/confirm-dialog.service';
import { DataTable, DataTableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Customer } from '../customer.model';
import { CustomerService } from '../customer.service';
import { CustomerFormDialog } from '../customer-form-dialog/customer-form-dialog';

@Component({
  selector: 'app-customer-list',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    DataTable,
    PageHeader,
  ],
  templateUrl: './customer-list.html',
})
export class CustomerList {
  private readonly customerService = inject(CustomerService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialogService = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly searchControl = new FormControl('', { nonNullable: true });

  protected readonly page = signal<PageResponse<Customer> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  private pageIndex = 0;
  private readonly pageSize = 20;
  private sort: Sort | null = null;

  protected readonly columns: DataTableColumn<Customer>[] = [
    { key: 'name', header: 'Name', sortable: true, cell: (c) => c.name },
    { key: 'email', header: 'Email', cell: (c) => c.email || '—' },
    { key: 'phone', header: 'Mobile', cell: (c) => c.phone || '—' },
    {
      key: 'gstin',
      header: 'Status',
      type: 'badge',
      cell: (c) => (c.gstin ? 'GST Registered' : 'Individual'),
      badgeClass: (c) =>
        c.gstin
          ? 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]'
          : 'bg-[var(--mat-sys-surface-container-highest)] text-[var(--mat-sys-on-surface-variant)]',
    },
    {
      key: 'createdAt',
      header: 'Created',
      sortable: true,
      align: 'right',
      cell: (c) => new Date(c.createdAt).toLocaleDateString(),
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

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    const params: Record<string, string | number> = { page: this.pageIndex, size: this.pageSize };
    const search = this.searchControl.value.trim();
    if (search) params['search'] = search;
    if (this.sort?.direction) params['sort'] = `${this.sort.active},${this.sort.direction}`;

    this.customerService.list(params).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load customers.');
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

  openCreateDialog(): void {
    this.dialog
      .open(CustomerFormDialog, { width: '480px', data: {} })
      .afterClosed()
      .subscribe((customer: Customer | undefined) => {
        if (customer) {
          this.snackBar.open('Customer added successfully', 'Dismiss', { duration: 4000 });
          this.load();
        }
      });
  }

  openEditDialog(customer: Customer): void {
    this.dialog
      .open(CustomerFormDialog, { width: '480px', data: { customer } })
      .afterClosed()
      .subscribe((updated: Customer | undefined) => {
        if (updated) {
          this.snackBar.open('Customer updated successfully', 'Dismiss', { duration: 4000 });
          this.load();
        }
      });
  }

  viewCustomer(customer: Customer): void {
    void this.router.navigate(['/customers', customer.id]);
  }

  deleteCustomer(customer: Customer): void {
    this.confirmDialogService
      .confirm({
        title: 'Delete customer?',
        message: `This will remove "${customer.name}" from your customer list. This cannot be undone.`,
        confirmLabel: 'Delete',
        danger: true,
      })
      .subscribe((confirmed) => {
        if (!confirmed) return;

        this.customerService.delete(customer.id).subscribe({
          next: () => {
            this.snackBar.open('Customer deleted successfully', 'Dismiss', { duration: 4000 });
            this.load();
          },
          error: (err: ApiError) => {
            this.snackBar.open(err.message, 'Dismiss', { duration: 6000 });
          },
        });
      });
  }

  retry(): void {
    this.load();
  }
}
