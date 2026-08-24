import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../core/api/api-client';
import { MonthlyReportRow, OutstandingReport, RevenueReport } from './report.model';

@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly api = inject(ApiClient);

  getRevenueReport(from: Date, to: Date): Observable<RevenueReport> {
    return this.api.get<RevenueReport>('/reports/revenue', {
      from: from.toISOString(),
      to: to.toISOString(),
    });
  }

  getOutstandingReport(): Observable<OutstandingReport> {
    return this.api.get<OutstandingReport>('/reports/outstanding');
  }

  getMonthlySummary(year: number): Observable<MonthlyReportRow[]> {
    return this.api.get<MonthlyReportRow[]>('/reports/monthly', { year });
  }
}
