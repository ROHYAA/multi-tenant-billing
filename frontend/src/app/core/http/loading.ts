import { Injectable, computed, signal } from '@angular/core';

/**
 * Tracks in-flight HTTP requests via a counter (not a boolean) so
 * overlapping requests don't cause one finishing early to hide the
 * indicator while others are still pending. Driven by loading-interceptor.
 */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  private readonly activeRequests = signal(0);
  readonly isLoading = computed(() => this.activeRequests() > 0);

  start(): void {
    this.activeRequests.update((count) => count + 1);
  }

  stop(): void {
    this.activeRequests.update((count) => Math.max(0, count - 1));
  }
}
