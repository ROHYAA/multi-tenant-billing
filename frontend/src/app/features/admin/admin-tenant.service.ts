import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../core/api/api-client';
import { PageResponse } from '../../core/models/api-response.model';
import { AdminTenant, ApproveTenantRequest, TenantStatus } from './admin-tenant.model';

@Injectable({ providedIn: 'root' })
export class AdminTenantService {
  private readonly api = inject(ApiClient);

  list(page: number, size: number, status?: TenantStatus): Observable<PageResponse<AdminTenant>> {
    const params: Record<string, string | number> = { page, size, sort: 'createdAt,desc' };
    if (status) params['status'] = status;
    return this.api.get<PageResponse<AdminTenant>>('/admin/tenants', params);
  }

  approve(tenantId: number, request: ApproveTenantRequest): Observable<AdminTenant> {
    return this.api.post<AdminTenant>(`/admin/tenants/${tenantId}/approve`, request);
  }

  reactivate(tenantId: number, request: ApproveTenantRequest): Observable<AdminTenant> {
    return this.api.post<AdminTenant>(`/admin/tenants/${tenantId}/reactivate`, request);
  }

  changeStatus(tenantId: number, status: TenantStatus, reason: string): Observable<AdminTenant> {
    return this.api.put<AdminTenant>(`/admin/tenants/${tenantId}/status`, { status, reason });
  }
}
