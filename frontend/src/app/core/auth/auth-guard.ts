import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth';

/**
 * No token to check — the app-boot /auth/me call (see app.config.ts's
 * provideAppInitializer) has already resolved CurrentUser by the time any
 * guard runs, so this is a plain synchronous signal read, not an async
 * round trip on every navigation.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isAuthenticated()) {
    return true;
  }

  return router.createUrlTree(['/login'], { queryParams: { redirectTo: state.url } });
};
