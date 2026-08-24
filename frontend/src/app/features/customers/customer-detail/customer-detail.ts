import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/models/api-response.model';
import { AuthService } from '../../../core/auth/auth';
import { ConfirmDialogService } from '../../../shared/components/confirm-dialog/confirm-dialog.service';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { CustomerFinancialSummary, Customer } from '../customer.model';
import { CustomerService } from '../customer.service';
import { CustomerFormDialog } from '../customer-form-dialog/customer-form-dialog';

@Component({
  selector: 'app-customer-detail',
  imports: [CurrencyPipe, DatePipe, MatButtonModule, MatCardModule, MatIconModule, PageHeader],
  templateUrl: './customer-detail.html',
})
export class CustomerDetail {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly customerService = inject(CustomerService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirmDialogService = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);

  private readonly customerId = Number(this.route.snapshot.paramMap.get('id'));

  protected readonly customer = signal<Customer | null>(null);
  protected readonly summary = signal<CustomerFinancialSummary | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly canViewBilling = this.authService.currentUser()?.permissions.includes('BILLING_MANAGE') ?? false;

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.customerService.getById(this.customerId).subscribe({
      next: (customer) => {
        this.customer.set(customer);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load customer.');
      },
    });

    if (this.canViewBilling) {
      this.customerService.getFinancialSummary(this.customerId).subscribe((summary) => this.summary.set(summary));
    }
  }

  editCustomer(): void {
    const customer = this.customer();
    if (!customer) return;

    this.dialog
      .open(CustomerFormDialog, { width: '480px', data: { customer } })
      .afterClosed()
      .subscribe((updated: Customer | undefined) => {
        if (updated) {
          this.customer.set(updated);
          this.snackBar.open('Customer updated successfully', 'Dismiss', { duration: 4000 });
        }
      });
  }

  deleteCustomer(): void {
    const customer = this.customer();
    if (!customer) return;

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
            void this.router.navigate(['/customers']);
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
