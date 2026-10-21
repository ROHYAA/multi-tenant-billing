import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Sort } from '@angular/material/sort';
import { ApiError, PageResponse } from '../../../core/models/api-response.model';
import { DataTable, DataTableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { EmployeeFormDialog } from '../employee-form-dialog/employee-form-dialog';
import { ShopUser } from '../staff.model';
import { StaffService } from '../staff.service';

@Component({
  selector: 'app-staff-list',
  imports: [MatButtonModule, MatIconModule, DataTable, PageHeader],
  templateUrl: './staff-list.html',
})
export class StaffList {
  private readonly staffService = inject(StaffService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly page = signal<PageResponse<ShopUser> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  private pageIndex = 0;
  private readonly pageSize = 20;
  private sort: Sort | null = null;

  protected readonly columns: DataTableColumn<ShopUser>[] = [
    { key: 'name', header: 'Name', sortable: true, cell: (u) => u.name },
    { key: 'email', header: 'Email', cell: (u) => u.email },
    {
      key: 'roleName',
      header: 'Role',
      type: 'badge',
      cell: (u) => u.roleName,
      badgeClass: (u) =>
        u.roleName === 'ADMIN' || u.roleName === 'OWNER'
          ? 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]'
          : 'bg-[var(--mat-sys-surface-container-highest)] text-[var(--mat-sys-on-surface-variant)]',
    },
    { key: 'status', header: 'Status', cell: (u) => u.status },
  ];

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    const params: Record<string, string | number> = { page: this.pageIndex, size: this.pageSize };
    if (this.sort?.direction) {
      params['sort'] = `${this.sort.active},${this.sort.direction}`;
    }
    this.staffService.list(params).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load staff.');
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
      .open(EmployeeFormDialog, { width: '420px' })
      .afterClosed()
      .subscribe((created) => {
        if (!created) return;
        this.snackBar.open(`${created.name} can now sign in as an employee.`, 'Dismiss', { duration: 5000 });
        this.load();
      });
  }

  retry(): void {
    this.load();
  }
}
