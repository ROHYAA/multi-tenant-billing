import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiClient } from '../api/api-client';
import { NativeSession } from './native-session';
import {
  AuthResponseDto,
  CurrentUser,
  LoginRequest,
  SignupRequest,
  UserProfileResponseDto,
  toCurrentUser,
  toCurrentUserFromProfile,
} from './current-user.model';

const TENANT_SLUG_KEY = 'shopledger.tenantSlug';

/**
 * No tokens live here — session existence is entirely cookie-driven,
 * server-side (see PRINTING_ARCHITECTURE.md's sibling doc on the backend
 * for context; the short version: access_token/refresh_token are HttpOnly
 * cookies, AuthResponse never includes one). This service only tracks the
 * one non-sensitive piece of state the backend actually needs from us —
 * tenantSlug — plus the CurrentUser signal every guard/component reads.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiClient);
  private readonly native = inject(NativeSession);

  private readonly currentUserSignal = signal<CurrentUser | null>(null);
  private readonly resolvedSignal = signal(false);

  /** Null until /auth/me resolves, or after logout / a failed refresh. */
  readonly currentUser = this.currentUserSignal.asReadonly();
  /** True once the app-boot /auth/me check has completed (success or 401) — guards wait on this. */
  readonly resolved = this.resolvedSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUserSignal() !== null);

  get tenantSlug(): string | null {
    return localStorage.getItem(TENANT_SLUG_KEY);
  }

  private set tenantSlug(slug: string | null) {
    if (slug) {
      localStorage.setItem(TENANT_SLUG_KEY, slug);
    } else {
      localStorage.removeItem(TENANT_SLUG_KEY);
    }
  }

  /** App boot finished with no session (native APK with no stored tokens). */
  markAnonymous(): void {
    this.currentUserSignal.set(null);
    this.resolvedSignal.set(true);
  }

  /**
   * Called once at app boot (see app.config.ts's provideAppInitializer).
   * /auth/me returns UserProfileResponse, NOT AuthResponse — a genuinely
   * different (flatter) shape, confirmed against the real endpoint rather
   * than assumed from the login/signup/refresh response shape.
   */
  bootstrapSession(): Observable<UserProfileResponseDto> {
    return this.api.get<UserProfileResponseDto>('/auth/me').pipe(
      tap({
        next: (dto) => {
          this.currentUserSignal.set(toCurrentUserFromProfile(dto));
          this.resolvedSignal.set(true);
        },
        error: () => this.resolvedSignal.set(true),
      }),
    );
  }

  login(request: LoginRequest): Observable<AuthResponseDto> {
    this.tenantSlug = request.tenantSlug;
    return this.api.post<AuthResponseDto>('/auth/login', request).pipe(
      tap((dto) => this.applySession(dto)),
    );
  }

  signup(request: SignupRequest): Observable<AuthResponseDto> {
    return this.api.post<AuthResponseDto>('/auth/signup', request).pipe(
      tap((dto) => this.applySession(dto)),
    );
  }

  /** Used by auth-interceptor on a 401 — refresh token comes from cookie (web) or JSON body (native). */
  refreshSession(): Observable<AuthResponseDto> {
    const body = this.native.isNative()
      ? { tenantSlug: this.tenantSlug, refreshToken: this.native.refreshToken() }
      : { tenantSlug: this.tenantSlug };
    return this.api.post<AuthResponseDto>('/auth/refresh', body).pipe(
      tap((dto) => this.applySession(dto)),
    );
  }

  logout(): Observable<void> {
    const refreshToken = this.native.isNative() ? (this.native.refreshToken() ?? '') : '';
    return this.api.post<void>('/auth/logout', { refreshToken }).pipe(
      tap(() => this.clearSession()),
    );
  }

  /** Called by auth-interceptor when a refresh attempt itself fails. */
  clearSession(): void {
    this.currentUserSignal.set(null);
    this.tenantSlug = null;
    this.resolvedSignal.set(true);
    void this.native.clear();
  }

  private applySession(dto: AuthResponseDto): void {
    this.currentUserSignal.set(toCurrentUser(dto));
    this.resolvedSignal.set(true);
    void this.native.persist(dto.tokens);
  }
}
