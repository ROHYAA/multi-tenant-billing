export type TenantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'REGISTERED' | 'PENDING_APPROVAL';

export interface AdminTenant {
  id: number;
  name: string;
  schemaName: string;
  status: TenantStatus;
  planName: string | null;
  subscriptionExpiresAt: string | null;
  userCount: number;
  createdAt: string;
}

export interface AdminLoginRequest {
  email: string;
  password: string;
}

/** Body for both POST /approve and POST /reactivate — offline-payment plan tracking. */
export interface ApproveTenantRequest {
  planName: string;
  /** ISO instant string — must be in the future. */
  subscriptionExpiresAt: string;
}
