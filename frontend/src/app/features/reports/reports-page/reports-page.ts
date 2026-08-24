import { KeyValuePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { ApiError, PageResponse } from '../../../core/models/api-response.model';
import { BarChart, BarChartPoint } from '../../../shared/components/bar-chart/bar-chart';
import { DataTable, DataTableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { MonthlyReportRow, OutstandingItem, OutstandingReport, RevenueReport } from '../report.model';
import { ReportService } from '../report.service';
import { exportToCsv } from '../../../shared/utils/csv-export';

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

function endOfToday(date: Date): Date {
  const d = new Date(date);
  d.setHours(23, 59, 59, 999);
  return d;
}

function toDateInputValue(date: Date): string {
  return date.toISOString().slice(0, 10);
}

@Component({
  selector: 'app-reports-page',
  imports: [
    FormsModule,
    KeyValuePipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTabsModule,
    BarChart,
    DataTable,
    PageHeader,
  ],
  templateUrl: './reports-page.html',
})
export class ReportsPage {
  private readonly reportService = inject(ReportService);

  // ── Revenue ───────────────────────────────────────────────────────────────
  protected fromDate = toDateInputValue(startOfMonth(new Date()));
  protected toDate = toDateInputValue(new Date());
  protected readonly revenueReport = signal<RevenueReport | null>(null);
  protected readonly revenueLoading = signal(false);
  protected readonly revenueError = signal<string | null>(null);

  // ── Outstanding ───────────────────────────────────────────────────────────
  protected readonly outstandingReport = signal<OutstandingReport | null>(null);
  protected readonly outstandingPage = signal<PageResponse<OutstandingItem> | null>(null);
  protected readonly outstandingLoading = signal(false);
  protected readonly outstandingError = signal<string | null>(null);

  protected readonly outstandingColumns: DataTableColumn<OutstandingItem>[] = [
    { key: 'invoiceNumber', header: 'Bill No.', cell: (i) => i.invoiceNumber },
    { key: 'totalAmount', header: 'Total', align: 'right', cell: (i) => this.formatCurrency(i.totalAmount) },
    {
      key: 'outstandingAmount',
      header: 'Outstanding',
      align: 'right',
      cell: (i) => this.formatCurrency(i.outstandingAmount),
    },
    {
      key: 'dueDate',
      header: 'Due',
      align: 'right',
      cell: (i) => (i.dueDate ? new Date(i.dueDate).toLocaleDateString() : '—'),
    },
    {
      key: 'overdue',
      header: 'Status',
      type: 'badge',
      cell: (i) => (i.overdue ? 'Overdue' : 'Current'),
      badgeClass: (i) =>
        i.overdue
          ? 'bg-[var(--mat-sys-error-container)] text-[var(--mat-sys-on-error-container)]'
          : 'bg-[var(--mat-sys-tertiary-container)] text-[var(--mat-sys-on-tertiary-container)]',
    },
  ];

  // ── Monthly ───────────────────────────────────────────────────────────────
  protected readonly years = Array.from({ length: 6 }, (_, i) => new Date().getFullYear() - i);
  protected selectedYear = new Date().getFullYear();
  protected readonly monthlyRows = signal<MonthlyReportRow[]>([]);
  protected readonly monthlyLoading = signal(false);
  protected readonly monthlyError = signal<string | null>(null);
  protected readonly monthlyChartData = signal<BarChartPoint[]>([]);

  constructor() {
    this.loadRevenue();
    this.loadOutstanding();
    this.loadMonthly();
  }

  loadRevenue(): void {
    const from = new Date(this.fromDate);
    const to = endOfToday(new Date(this.toDate));
    if (from > to) {
      this.revenueError.set("'From' date must be before 'To' date.");
      return;
    }

    this.revenueLoading.set(true);
    this.revenueError.set(null);
    this.reportService.getRevenueReport(from, to).subscribe({
      next: (report) => {
        this.revenueReport.set(report);
        this.revenueLoading.set(false);
      },
      error: (err: ApiError) => {
        this.revenueLoading.set(false);
        this.revenueError.set(err.message || 'Failed to load revenue report.');
      },
    });
  }

  private loadOutstanding(): void {
    this.outstandingLoading.set(true);
    this.outstandingError.set(null);
    this.reportService.getOutstandingReport().subscribe({
      next: (report) => {
        this.outstandingReport.set(report);
        this.outstandingPage.set({
          content: report.items,
          page: 0,
          size: Math.max(report.items.length, 1),
          totalElements: report.items.length,
          totalPages: 1,
          first: true,
          last: true,
          empty: report.items.length === 0,
          hasNext: false,
          hasPrevious: false,
        });
        this.outstandingLoading.set(false);
      },
      error: (err: ApiError) => {
        this.outstandingLoading.set(false);
        this.outstandingError.set(err.message || 'Failed to load outstanding report.');
      },
    });
  }

  loadMonthly(): void {
    this.monthlyLoading.set(true);
    this.monthlyError.set(null);
    this.reportService.getMonthlySummary(this.selectedYear).subscribe({
      next: (rows) => {
        this.monthlyRows.set(rows);
        this.monthlyChartData.set(rows.map((r) => ({ label: r.monthName, value: r.collected })));
        this.monthlyLoading.set(false);
      },
      error: (err: ApiError) => {
        this.monthlyLoading.set(false);
        this.monthlyError.set(err.message || 'Failed to load monthly report.');
      },
    });
  }

  // ── Export ────────────────────────────────────────────────────────────────

  exportRevenue(): void {
    const report = this.revenueReport();
    if (!report) return;

    const rows: Record<string, string | number>[] = [
      { Metric: 'From', Value: new Date(report.from).toLocaleDateString() },
      { Metric: 'To', Value: new Date(report.to).toLocaleDateString() },
      { Metric: 'Total Revenue', Value: report.totalRevenue },
      { Metric: 'Paid Bills', Value: report.paidInvoiceCount },
      { Metric: 'Average Bill Value', Value: report.averageInvoiceValue },
    ];
    for (const [method, amount] of Object.entries(report.revenueByMethod)) {
      rows.push({ Metric: `Revenue — ${method}`, Value: amount ?? 0 });
    }
    exportToCsv(`revenue-report_${this.fromDate}_to_${this.toDate}`, rows);
  }

  exportOutstanding(): void {
    const items = this.outstandingReport()?.items ?? [];
    if (items.length === 0) return;

    exportToCsv(
      `outstanding-report_${toDateInputValue(new Date())}`,
      items.map((i) => ({
        'Bill No.': i.invoiceNumber,
        Total: i.totalAmount,
        Outstanding: i.outstandingAmount,
        'Due Date': i.dueDate ? new Date(i.dueDate).toLocaleDateString() : '',
        Status: i.overdue ? 'Overdue' : 'Current',
      })),
    );
  }

  exportMonthly(): void {
    const rows = this.monthlyRows();
    if (rows.length === 0) return;

    exportToCsv(
      `monthly-report_${this.selectedYear}`,
      rows.map((r) => ({
        Month: r.monthName,
        Bills: r.invoiceCount,
        Invoiced: r.invoiceTotal,
        Collected: r.collected,
        Outstanding: r.outstanding,
      })),
    );
  }

  protected currencyFormatter = (value: number) => this.formatCurrency(value);

  protected formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(
      value,
    );
  }
}
