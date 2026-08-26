export type TenantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'REGISTERED' | 'PENDING_APPROVAL';

/** Mirrors com.mtbs.auth.dto.auth.AuthResponse — flattened for frontend convenience. */
export interface CurrentUser {
  userId: number;
  email: string;
  role: string;
  permissions: string[];
  tenantId: number | null;
  tenantName: string | null;
  /** The SHOP's status, not the user's — null for a SUPER_ADMIN session (no tenant). */
  tenantStatus: TenantStatus | null;
  /** Offline-payment plan tracking — null until an admin approves/reactivates with a plan set. */
  planName: string | null;
  subscriptionExpiresAt: string | null;
  isFirstLogin: boolean;
  isTrial: boolean;
  requiresOnboarding: boolean;
}

export interface LoginRequest {
  email: string;
  password: string;
  tenantSlug: string;
}

export interface SignupRequest {
  name: string;
  email: string;
  password: string;
}

/** Raw shape of com.mtbs.auth.dto.auth.AuthResponse, before flattening into CurrentUser. */
export interface AuthResponseDto {
  user: { userId: number; email: string; role: string; permissions: string[] | null };
  tenant: {
    tenantId: number;
    tenantName: string;
    tenantStatus: TenantStatus;
    planName: string | null;
    subscriptionExpiresAt: string | null;
  } | null;
  session: { issuedAt: string; expiresAt: string; isFirstLogin: boolean };
  flags: { isTrial: boolean; requiresOnboarding: boolean } | null;
}

export function toCurrentUser(dto: AuthResponseDto): CurrentUser {
  return {
    userId: dto.user.userId,
    email: dto.user.email,
    role: dto.user.role,
    permissions: dto.user.permissions ?? [],
    tenantId: dto.tenant?.tenantId ?? null,
    tenantName: dto.tenant?.tenantName ?? null,
    tenantStatus: dto.tenant?.tenantStatus ?? null,
    planName: dto.tenant?.planName ?? null,
    subscriptionExpiresAt: dto.tenant?.subscriptionExpiresAt ?? null,
    isFirstLogin: dto.session.isFirstLogin,
    isTrial: dto.flags?.isTrial ?? false,
    requiresOnboarding: dto.flags?.requiresOnboarding ?? false,
  };
}

/**
 * Raw shape of com.mtbs.auth.dto.auth.UserProfileResponse — GET /auth/me's
 * response. Deliberately NOT the same shape as AuthResponseDto (flat, not
 * nested user/tenant/session/flags) — confirmed by hitting the real
 * endpoint, not assumed. No session/flags data since /auth/me is a profile
 * read, not a login event; isFirstLogin/isTrial/requiresOnboarding default
 * to false for a restored session (they only matter at the moment of login).
 */
export interface UserProfileResponseDto {
  userId: number;
  name: string;
  email: string;
  role: string;
  status: string;
  tenantId: number;
  tenantName?: string;
  /** The SHOP's status, not the user's (that's the sibling `status` field). */
  tenantStatus?: TenantStatus;
  planName?: string;
  subscriptionExpiresAt?: string;
  schemaName: string;
  permissions?: string[];
}

export function toCurrentUserFromProfile(dto: UserProfileResponseDto): CurrentUser {
  return {
    userId: dto.userId,
    email: dto.email,
    role: dto.role,
    permissions: dto.permissions ?? [],
    tenantId: dto.tenantId,
    tenantName: dto.tenantName ?? null,
    tenantStatus: dto.tenantStatus ?? null,
    planName: dto.planName ?? null,
    subscriptionExpiresAt: dto.subscriptionExpiresAt ?? null,
    isFirstLogin: false,
    isTrial: false,
    requiresOnboarding: false,
  };
}
