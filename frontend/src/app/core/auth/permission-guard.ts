import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth';

/**
 * Route usage: { path: 'customers', canActivate: [authGuard, permissionGuard],
 *                data: { permission: 'CUSTOMER_MANAGE' } }
 *
 * `permission` matches the backend's real PERMISSION_* authorities exactly
 * (CUSTOMER_MANAGE, PRODUCT_MANAGE, BILLING_MANAGE, TENANT_MANAGE, ...) as
 * seeded in public.permissions — see V4__seed_permissions.sql. Runs after
 * authGuard, so CurrentUser is guaranteed non-null here.
 */
export const permissionGuard: CanActivateFn = (route) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const requiredPermission = route.data['permission'] as string | undefined;
  if (!requiredPermission) {
    return true; // Route didn't declare a permission requirement — nothing to check.
  }

  const permissions = authService.currentUser()?.permissions ?? [];
  if (permissions.includes(requiredPermission)) {
    return true;
  }

  return router.createUrlTree(['/forbidden']);
};
