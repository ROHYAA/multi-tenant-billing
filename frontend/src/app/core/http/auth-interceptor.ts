import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../auth/auth';

const AUTH_ENDPOINTS = [
  '/auth/login',
  '/auth/signup',
  '/auth/refresh',
  // /auth/me is the app-boot "do I have a tenant session?" check (see
  // AuthService.bootstrapSession) — it already has its own 401 handling
  // (mark resolved, leave currentUser null) and runs on every page load,
  // including public/admin pages with no tenant session at all. Without
  // this exclusion, that routine 401 triggered a refresh attempt (which
  // 500s with no session to refresh) and then force-navigated to /login —
  // silently yanking anyone away from e.g. /admin/login while they were
  // trying to sign in there.
  '/auth/me',
  // Admin is a completely separate auth system (own cookies, own /admin/auth/*
  // endpoints) — a 401 here must never trigger a tenant-session refresh/redirect.
  '/admin/',
];

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
