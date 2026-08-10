import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../auth/auth';

const AUTH_ENDPOINTS = ['/auth/login', '/auth/signup', '/auth/refresh'];

/**
 * (1) Attaches cookies to every request — there is no token to attach, the
 *     browser sends access_token/refresh_token automatically once
 *     withCredentials is set.
 * (2) On a 401 from anything other than the auth endpoints themselves,
 *     attempts exactly one silent POST /auth/refresh and retries the
 *     original request. A second failure clears the session and redirects
 *     to /login — this is the one piece of real complexity here.
 *
 * Registered before api-response-interceptor (see app.config.ts) so it
 * operates on the raw HttpErrorResponse, not an already-thrown ApiError.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const credentialedReq = req.clone({ withCredentials: true });
  const isAuthEndpoint = AUTH_ENDPOINTS.some((endpoint) => req.url.includes(endpoint));

  return next(credentialedReq).pipe(
    catchError((error: unknown) => {
      const isUnauthorized = error instanceof HttpErrorResponse && error.status === 401;

      if (isUnauthorized && !isAuthEndpoint) {
        return authService.refreshSession().pipe(
          switchMap(() => next(credentialedReq)),
          catchError(() => {
            authService.clearSession();
            void router.navigate(['/login']);
            return throwError(() => error);
          }),
        );
      }

      return throwError(() => error);
    }),
  );
};
