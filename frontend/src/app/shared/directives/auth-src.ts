import { HttpClient } from '@angular/common/http';
import { DestroyRef, Directive, ElementRef, effect, inject, input } from '@angular/core';
import { Subscription } from 'rxjs';
import { NativeSession } from '../../core/auth/native-session';

/**
 * <img [appAuthSrc]="url"> for images served by the API (logos, signatures, photos).
 *
 * Browser: the session cookie authenticates the image request, so the URL is used as-is.
 * Android app: auth is a Bearer token that <img> cannot send, so the image is fetched
 * through HttpClient (auth interceptor adds the header) and shown from a blob URL.
 */
@Directive({ selector: 'img[appAuthSrc]' })
export class AuthSrc {
  readonly appAuthSrc = input<string | null | undefined>(null);

  private readonly img = inject<ElementRef<HTMLImageElement>>(ElementRef).nativeElement;
  private readonly http = inject(HttpClient);
  private readonly native = inject(NativeSession);
  private objectUrl: string | null = null;
  private request: Subscription | null = null;

  constructor() {
    effect(() => this.load(this.appAuthSrc()));
    inject(DestroyRef).onDestroy(() => this.reset());
  }

  private load(url: string | null | undefined): void {
    this.reset();
    if (!url) {
      this.img.removeAttribute('src');
      return;
    }
    if (!this.native.isNative()) {
      this.img.crossOrigin = 'use-credentials';
      this.img.src = url;
      return;
    }
    this.request = this.http.get(url, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        this.objectUrl = URL.createObjectURL(blob);
        this.img.src = this.objectUrl;
      },
      error: () => this.img.removeAttribute('src'),
    });
  }

  private reset(): void {
    this.request?.unsubscribe();
    this.request = null;
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = null;
    }
  }
}
