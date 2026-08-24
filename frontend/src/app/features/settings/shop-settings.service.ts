import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from '../../core/api/api-client';
import { environment } from '../../../environments/environment';
import { Attachment, AttachmentPurpose, BillTemplate, ShopSettings, UpdateShopSettingsRequest } from './shop-settings.model';

@Injectable({ providedIn: 'root' })
export class ShopSettingsService {
  private readonly api = inject(ApiClient);

  /**
   * "http://localhost:8080" from an apiBaseUrl of ".../api/v1" — attachment urls
   * already include the /api/v1 prefix. window.location.origin is passed as the
   * base so this also works when apiBaseUrl is relative (environment.prod.ts uses
   * "/api/v1" for same-origin deployments, which `new URL()` alone can't parse).
   */
  private readonly backendOrigin = new URL(environment.apiBaseUrl, window.location.origin).origin;

  getSettings(): Observable<ShopSettings> {
    return this.api.get<ShopSettings>('/shop-settings');
  }

  updateSettings(request: UpdateShopSettingsRequest): Observable<ShopSettings> {
    return this.api.put<ShopSettings>('/shop-settings', request);
  }

  listBillTemplates(): Observable<BillTemplate[]> {
    return this.api.get<BillTemplate[]>('/bill-templates');
  }

  uploadAttachment(purpose: AttachmentPurpose, file: File): Observable<Attachment> {
    const formData = new FormData();
    formData.append('file', file);
    return this.api.postFormData<Attachment>('/attachments', formData, { purpose });
  }

  /** Resolves a relative attachment url (e.g. from logoUrl) to a fully-qualified, loadable <img src>. */
  resolveAttachmentUrl(relativeUrl: string | null | undefined): string | null {
    return relativeUrl ? this.backendOrigin + relativeUrl : null;
  }
}
