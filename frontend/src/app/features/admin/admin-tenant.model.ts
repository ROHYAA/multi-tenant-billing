export type TenantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'REGISTERED' | 'PENDING_APPROVAL';

export interface AdminTenant {
  id: number;
  name: string;
  schemaName: string;
  status: TenantStatus;
  userCount: number;
  createdAt: string;
}

export interface AdminLoginRequest {
  email: string;
  password: string;
}
