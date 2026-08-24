import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
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
import { Product } from '../product.model';
import { ProductService } from '../product.service';
import { ProductFormDialog } from '../product-form-dialog/product-form-dialog';

@Component({
  selector: 'app-product-list',
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
  templateUrl: './product-list.html',
})
export class ProductList {
  private readonly productService = inject(ProductService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialogService = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly searchControl = new FormControl('', { nonNullable: true });

  protected readonly page = signal<PageResponse<Product> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  private pageIndex = 0;
  private readonly pageSize = 20;
  private sort: Sort | null = null;

  protected readonly columns: DataTableColumn<Product>[] = [
    { key: 'name', header: 'Name', sortable: true, cell: (p) => p.name },
    { key: 'unit', header: 'Unit', cell: (p) => p.unit || '—' },
    { key: 'price', header: 'Price', align: 'right', cell: (p) => this.formatCurrency(p.price) },
    { key: 'taxPercentage', header: 'Tax %', align: 'right', cell: (p) => `${p.taxPercentage}%` },
    {
      key: 'isActive',
      header: 'Status',
      type: 'badge',
      cell: (p) => (p.isActive ? 'Active' : 'Inactive'),
      badgeClass: (p) =>
        p.isActive
          ? 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]'
          : 'bg-[var(--mat-sys-surface-container-highest)] text-[var(--mat-sys-on-surface-variant)]',
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

    this.productService.list(params).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load products.');
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
      .open(ProductFormDialog, { width: '480px', data: {} })
      .afterClosed()
      .subscribe((product: Product | undefined) => {
        if (product) {
          this.snackBar.open('Product added successfully', 'Dismiss', { duration: 4000 });
          this.load();
        }
      });
  }

  openEditDialog(product: Product): void {
    this.dialog
      .open(ProductFormDialog, { width: '480px', data: { product } })
      .afterClosed()
      .subscribe((updated: Product | undefined) => {
        if (updated) {
          this.snackBar.open('Product updated successfully', 'Dismiss', { duration: 4000 });
          this.load();
        }
      });
  }

  toggleActive(product: Product): void {
    if (product.isActive) {
      this.confirmDialogService
        .confirm({
          title: 'Deactivate product?',
          message: `"${product.name}" will no longer be available to add to new bills. Historical bills are unaffected.`,
          confirmLabel: 'Deactivate',
          danger: true,
        })
        .subscribe((confirmed) => {
          if (!confirmed) return;
          this.productService.deactivate(product.id).subscribe({
            next: () => {
              this.snackBar.open('Product deactivated', 'Dismiss', { duration: 4000 });
              this.load();
            },
            error: (err: ApiError) => this.snackBar.open(err.message, 'Dismiss', { duration: 6000 }),
          });
        });
    } else {
      this.productService.reactivate(product.id).subscribe({
        next: () => {
          this.snackBar.open('Product reactivated', 'Dismiss', { duration: 4000 });
          this.load();
        },
        error: (err: ApiError) => this.snackBar.open(err.message, 'Dismiss', { duration: 6000 }),
      });
    }
  }

  retry(): void {
    this.load();
  }

  private formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(value);
  }
}
