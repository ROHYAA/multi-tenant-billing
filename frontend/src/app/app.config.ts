import {
  ApplicationConfig,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
  inject,
  isDevMode,
} from '@angular/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideServiceWorker } from '@angular/service-worker';
import { firstValueFrom, catchError, of } from 'rxjs';

import { routes } from './app.routes';
import { AuthService } from './core/auth/auth';
import { apiResponseInterceptor } from './core/http/api-response-interceptor';
import { authInterceptor } from './core/http/auth-interceptor';
import { loadingInterceptor } from './core/http/loading-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideAnimationsAsync(),

    // Order matters: first = outermost. loadingInterceptor tracks the whole
    // visible request lifecycle (including a retry); apiResponseInterceptor
    // unwraps the envelope; authInterceptor is innermost so its 401-retry
    // logic sees the raw HttpErrorResponse, not an already-thrown ApiError.
    provideHttpClient(withInterceptors([loadingInterceptor, apiResponseInterceptor, authInterceptor])),

    // Resolves CurrentUser (or confirms "not logged in") before the app
    // renders anything — a 401 here is an expected, valid outcome (not
    // logged in yet), so it must not fail app bootstrap.
    provideAppInitializer(() => {
      const authService = inject(AuthService);
      return firstValueFrom(authService.bootstrapSession().pipe(catchError(() => of(null))));
    }),

    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
