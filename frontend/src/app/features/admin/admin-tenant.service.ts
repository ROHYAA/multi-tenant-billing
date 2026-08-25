import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../core/api/api-client';
import { PageResponse } from '../../core/models/api-response.model';
import { AdminTenant, TenantStatus } from './admin-tenant.model';

@Injectable({ providedIn: 'root' })
export class AdminTenantService {
  private readonly api = inject(ApiClient);

  list(page: number, size: number, status?: TenantStatus): Observable<PageResponse<AdminTenant>> {
    const params: Record<string, string | number> = { page, size, sort: 'createdAt,desc' };
    if (status) params['status'] = status;
    return this.api.get<PageResponse<AdminTenant>>('/admin/tenants', params);
  }

  approve(tenantId: number): Observable<AdminTenant> {
    return this.api.post<AdminTenant>(`/admin/tenants/${tenantId}/approve`);
  }
}
