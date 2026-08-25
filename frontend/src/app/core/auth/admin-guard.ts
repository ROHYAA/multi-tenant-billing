import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AdminAuthService } from './admin-auth';

/**
 * Unlike authGuard, this is a genuine async round trip (GET /admin/auth/me)
 * on every navigation into /admin/tenants — admin sessions aren't
 * bootstrapped at app boot, since almost no visit to the app touches them.
 */
export const adminGuard: CanActivateFn = (_route, state) => {
  const adminAuth = inject(AdminAuthService);
  const router = inject(Router);

  return adminAuth.checkSession().pipe(
    map((isAuthenticated) =>
      isAuthenticated ? true : router.createUrlTree(['/admin/login'], { queryParams: { redirectTo: state.url } }),
    ),
  );
};
