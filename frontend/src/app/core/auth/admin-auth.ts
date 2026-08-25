import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { AdminLoginRequest } from '../../features/admin/admin-tenant.model';

interface AdminProfile {
  userId: number;
  email: string;
  role: string;
}

/**
 * Separate auth system from AuthService — SUPER_ADMIN platform admins are
 * not tenant-scoped and use their own /api/v1/admin/auth/* endpoints and
 * cookies. Deliberately not bootstrapped at app boot (unlike AuthService):
 * admin routes are rare, so the session check only happens lazily, in
 * adminGuard, the first time someone actually navigates to /admin/tenants.
 */
@Injectable({ providedIn: 'root' })
export class AdminAuthService {
  private readonly api = inject(ApiClient);

  login(request: AdminLoginRequest): Observable<AdminProfile> {
    return this.api.post<{ user: AdminProfile }>('/admin/auth/login', request).pipe(
      map((res) => res.user),
    );
  }

  logout(): Observable<void> {
    return this.api.post<void>('/admin/auth/logout');
  }

  /** True if there's a valid admin session right now — used by adminGuard. */
  checkSession(): Observable<boolean> {
    return this.api.get<AdminProfile>('/admin/auth/me').pipe(
      map(() => true),
      catchError(() => of(false)),
    );
  }
}
