import { PaymentMethod } from '../billing/bill.model';

/** Mirrors com.mtbs.business.report.dto.RevenueReportResponse. */
export interface RevenueReport {
  from: string;
  to: string;
  totalRevenue: number;
  paidInvoiceCount: number;
  averageInvoiceValue: number;
  /** Only methods with at least one payment in the period are included. */
  revenueByMethod: Partial<Record<PaymentMethod, number>>;
}

/** Mirrors com.mtbs.business.report.dto.OutstandingReportResponse.OutstandingItem. */
export interface OutstandingItem {
  invoiceId: number;
  invoiceNumber: string;
  customerId: number;
  totalAmount: number;
  outstandingAmount: number;
  dueDate: string | null;
  overdue: boolean;
}

/** Mirrors com.mtbs.business.report.dto.OutstandingReportResponse. */
export interface OutstandingReport {
  totalOutstanding: number;
  overdueAmount: number;
  overdueCount: number;
  currentAmount: number;
  currentCount: number;
  items: OutstandingItem[];
}

/** Mirrors com.mtbs.business.report.dto.MonthlyReportRow. */
export interface MonthlyReportRow {
  year: number;
  month: number;
  monthName: string;
  invoiceCount: number;
  invoiceTotal: number;
  collected: number;
  outstanding: number;
}
