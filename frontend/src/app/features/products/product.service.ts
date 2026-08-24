import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, QueryParams } from '../../core/api/api-client';
import { PageResponse } from '../../core/models/api-response.model';
import { CreateProductRequest, Product, UpdateProductRequest } from './product.model';

@Injectable({ providedIn: 'root' })
export class ProductService {
  private readonly api = inject(ApiClient);

  list(params: QueryParams): Observable<PageResponse<Product>> {
    return this.api.get<PageResponse<Product>>('/products', params);
  }

  getById(id: number): Observable<Product> {
    return this.api.get<Product>(`/products/${id}`);
  }

  create(request: CreateProductRequest): Observable<Product> {
    return this.api.post<Product>('/products', request);
  }

  update(id: number, request: UpdateProductRequest): Observable<Product> {
    return this.api.put<Product>(`/products/${id}`, request);
  }

  /** Deactivates — products are never hard-deleted (historical bill line items reference them). */
  deactivate(id: number): Observable<void> {
    return this.api.delete<void>(`/products/${id}`);
  }

  reactivate(id: number): Observable<void> {
    return this.api.post<void>(`/products/${id}/reactivate`);
  }
}
