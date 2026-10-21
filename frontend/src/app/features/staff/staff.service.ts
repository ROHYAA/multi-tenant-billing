import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, QueryParams } from '../../core/api/api-client';
import { PageResponse } from '../../core/models/api-response.model';
import { CreateEmployeeRequest, ShopUser } from './staff.model';

@Injectable({ providedIn: 'root' })
export class StaffService {
  private readonly api = inject(ApiClient);

  list(params: QueryParams): Observable<PageResponse<ShopUser>> {
    return this.api.get<PageResponse<ShopUser>>('/users', params);
  }

  createEmployee(request: CreateEmployeeRequest): Observable<ShopUser> {
    return this.api.post<ShopUser>('/users', request);
  }
}
