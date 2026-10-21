import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, QueryParams } from '../../core/api/api-client';
import { PageResponse } from '../../core/models/api-response.model';
import {
  CreateUsedPhoneRequest,
  ImeiHistory,
  UploadedPhoto,
  UsedPhonePhotoPurpose,
  UsedPhonePurchase,
} from './used-phone.model';

@Injectable({ providedIn: 'root' })
export class UsedPhoneService {
  private readonly api = inject(ApiClient);

  list(params: QueryParams): Observable<PageResponse<UsedPhonePurchase>> {
    return this.api.get<PageResponse<UsedPhonePurchase>>('/used-phones', params);
  }

  getById(id: number): Observable<UsedPhonePurchase> {
    return this.api.get<UsedPhonePurchase>(`/used-phones/${id}`);
  }

  create(request: CreateUsedPhoneRequest): Observable<UsedPhonePurchase> {
    return this.api.post<UsedPhonePurchase>('/used-phones', request);
  }

  imeiHistory(imei: string): Observable<ImeiHistory> {
    return this.api.get<ImeiHistory>('/used-phones/imei-check', { imei });
  }

  uploadPhoto(purpose: UsedPhonePhotoPurpose, file: Blob, fileName: string): Observable<UploadedPhoto> {
    const formData = new FormData();
    formData.append('file', file, fileName);
    return this.api.postFormData<UploadedPhoto>('/used-phones/photos', formData, { purpose });
  }

  voucherPdf(id: number): Observable<Blob> {
    return this.api.getPdf(`/used-phones/${id}/voucher`);
  }
}
