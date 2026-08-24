import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../../core/auth/auth';
import { BarChart, BarChartPoint } from '../../../shared/components/bar-chart/bar-chart';
import { Bill } from '../../billing/bill.model';
import { BillService } from '../../billing/bill.service';
import { OutstandingReport, RevenueReport } from '../../reports/report.model';
import { ReportService } from '../../reports/report.service';

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

@Component({
  selector: 'app-dashboard-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, BarChart],
  templateUrl: './dashboard-page.html',
})
export class DashboardPage {
  private readonly authService = inject(AuthService);
  private readonly billService = inject(BillService);
  private readonly reportService = inject(ReportService);

  protected readonly userName = this.authService.currentUser()?.email ?? '';
  protected readonly canViewBilling = this.authService.currentUser()?.permissions.includes('BILLING_MANAGE') ?? false;

  protected readonly loading = signal(true);
  protected readonly revenueReport = signal<RevenueReport | null>(null);
  protected readonly outstandingReport = signal<OutstandingReport | null>(null);
  protected readonly recentBills = signal<Bill[]>([]);
  protected readonly chartData = signal<BarChartPoint[]>([]);

  protected currencyFormatter = (value: number) => this.formatCurrency(value);

  constructor() {
    // BillController and ReportController are both gated on BILLING_MANAGE —
    // a user without it (e.g. CUSTOMER_MANAGE-only) gets a bare welcome dashboard.
    if (!this.canViewBilling) {
      this.loading.set(false);
      return;
    }

    this.billService.list({ page: 0, size: 5, sort: 'createdAt,desc' }).subscribe({
      next: (page) => this.recentBills.set(page.content),
      error: () => this.recentBills.set([]),
    });

    this.reportService.getRevenueReport(startOfMonth(new Date()), new Date()).subscribe({
      next: (report) => this.revenueReport.set(report),
      error: () => this.revenueReport.set(null),
    });

    this.reportService.getOutstandingReport().subscribe({
      next: (report) => this.outstandingReport.set(report),
      error: () => this.outstandingReport.set(null),
    });

    this.reportService.getMonthlySummary(new Date().getFullYear()).subscribe({
      next: (rows) => {
        this.chartData.set(rows.map((r) => ({ label: r.monthName, value: r.collected })));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected billStatusClass(status: Bill['status']): string {
    switch (status) {
      case 'PAID':
        return 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]';
      case 'OPEN':
        return 'bg-[var(--mat-sys-secondary-container)] text-[var(--mat-sys-on-secondary-container)]';
      case 'VOID':
      case 'UNCOLLECTIBLE':
        return 'bg-[var(--mat-sys-error-container)] text-[var(--mat-sys-on-error-container)]';
      default:
        return 'bg-[var(--mat-sys-surface-container-highest)] text-[var(--mat-sys-on-surface-variant)]';
    }
  }

  private formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(
      value,
    );
  }
}
