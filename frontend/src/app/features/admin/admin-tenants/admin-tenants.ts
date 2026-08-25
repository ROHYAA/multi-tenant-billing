import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Sort } from '@angular/material/sort';
import { ApiError, PageResponse } from '../../../core/models/api-response.model';
import { AdminAuthService } from '../../../core/auth/admin-auth';
import { DataTable, DataTableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { AdminTenant, TenantStatus } from '../admin-tenant.model';
import { AdminTenantService } from '../admin-tenant.service';
import { Router } from '@angular/router';

@Component({
  selector: 'app-admin-tenants',
  imports: [MatButtonModule, MatChipsModule, MatIconModule, DataTable, PageHeader],
  templateUrl: './admin-tenants.html',
})
export class AdminTenants {
  private readonly tenantService = inject(AdminTenantService);
  private readonly adminAuth = inject(AdminAuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);

  protected readonly page = signal<PageResponse<AdminTenant> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly approvingId = signal<number | null>(null);
  protected readonly pendingOnly = signal(true);

  private pageIndex = 0;
  private readonly pageSize = 20;
  private sort: Sort | null = null;

  protected readonly columns: DataTableColumn<AdminTenant>[] = [
    { key: 'name', header: 'Shop', sortable: true, cell: (t) => t.name },
    { key: 'schemaName', header: 'Schema', cell: (t) => t.schemaName },
    {
      key: 'status',
      header: 'Status',
      type: 'badge',
      cell: (t) => t.status,
      badgeClass: (t) =>
        t.status === 'PENDING_APPROVAL'
          ? 'bg-[var(--mat-sys-error-container)] text-[var(--mat-sys-on-error-container)]'
          : t.status === 'ACTIVE'
            ? 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]'
            : 'bg-[var(--mat-sys-surface-container-highest)] text-[var(--mat-sys-on-surface-variant)]',
    },
    { key: 'userCount', header: 'Users', align: 'right', cell: (t) => String(t.userCount) },
    { key: 'createdAt', header: 'Signed up', sortable: true, align: 'right', cell: (t) => new Date(t.createdAt).toLocaleDateString() },
  ];

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    const status: TenantStatus | undefined = this.pendingOnly() ? 'PENDING_APPROVAL' : undefined;
    this.tenantService.list(this.pageIndex, this.pageSize, status).subscribe({
      next: (page) => {
        this.page.set(page);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load tenants.');
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

  toggleFilter(): void {
    this.pendingOnly.set(!this.pendingOnly());
    this.pageIndex = 0;
    this.load();
  }

  approve(tenant: AdminTenant): void {
    this.approvingId.set(tenant.id);
    this.tenantService.approve(tenant.id).subscribe({
      next: () => {
        this.approvingId.set(null);
        this.snackBar.open(`${tenant.name} approved — the owner can now create/edit/delete.`, 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err: ApiError) => {
        this.approvingId.set(null);
        this.snackBar.open(err.message, 'Dismiss', { duration: 6000 });
      },
    });
  }

  retry(): void {
    this.load();
  }

  logout(): void {
    this.adminAuth.logout().subscribe(() => void this.router.navigateByUrl('/admin/login'));
  }
}
