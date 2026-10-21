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
import { firstValueFrom, catchError, of, switchMap, timeout } from 'rxjs';
import { SplashScreen } from '@capacitor/splash-screen';
import { environment } from '../environments/environment';

import { routes } from './app.routes';
import { AuthService } from './core/auth/auth';
import { NativeSession } from './core/auth/native-session';
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
      const native = inject(NativeSession);
      const authService = inject(AuthService);
      return native
        .hydrate()
        .catch(() => undefined)
        .then(() => {
          if (environment.nativeClient && !native.accessToken()) {
            authService.markAnonymous();
            return null;
          }
          // App reopened after the 15-minute access token expired: /auth/me is excluded
          // from the interceptor's refresh, so renew once here with the stored refresh
          // token instead of sending the shopkeeper back to the login screen.
          const canRefresh = native.isNative() && !!native.refreshToken();
          const session$ = canRefresh
            ? authService
                .bootstrapSession()
                .pipe(catchError(() => authService.refreshSession().pipe(switchMap(() => authService.bootstrapSession()))))
            : authService.bootstrapSession();
          return firstValueFrom(
            session$.pipe(
              timeout({ first: canRefresh ? 10000 : 4000 }),
              catchError(() => {
                authService.markAnonymous();
                return of(null);
              }),
            ),
          );
        })
        .finally(() => SplashScreen.hide().catch(() => undefined));
    }),

    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode() && !environment.nativeClient,
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
