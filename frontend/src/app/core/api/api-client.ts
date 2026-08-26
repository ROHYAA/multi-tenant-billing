import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type QueryParams = Record<string, string | number | boolean | undefined | null>;

/**
 * Thin typed wrapper over HttpClient. By the time a call resolves here,
 * api-response-interceptor has already unwrapped ApiResponse<T> down to T
 * (or thrown an ApiError) — feature services built on this never see the
 * envelope. withCredentials is set globally by auth-interceptor, not here.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.apiBaseUrl;

  get<T>(path: string, params?: QueryParams): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}${path}`, { params: this.buildParams(params) });
  }

  post<T>(path: string, body: unknown = {}): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body);
  }

  put<T>(path: string, body: unknown = {}): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body);
  }

  patch<T>(path: string, body: unknown = {}): Observable<T> {
    return this.http.patch<T>(`${this.baseUrl}${path}`, body);
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${path}`);
  }

  /** Multipart file upload — e.g. attachment logos/signatures. */
  postFormData<T>(path: string, formData: FormData, params?: QueryParams): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, formData, { params: this.buildParams(params) });
  }

  /** For PDF download/preview endpoints — response is a raw Blob, not an ApiResponse envelope. */
  getBlob(path: string, params?: QueryParams): Observable<Blob> {
    return this.http.get(`${this.baseUrl}${path}`, { params: this.buildParams(params), responseType: 'blob' });
  }

  private buildParams(params?: QueryParams): HttpParams {
    let httpParams = new HttpParams();
    if (!params) return httpParams;
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) {
        httpParams = httpParams.set(key, String(value));
      }
    }
    return httpParams;
  }
}
