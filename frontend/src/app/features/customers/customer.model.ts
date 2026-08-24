/** Mirrors com.mtbs.business.customer.dto.CustomerResponse. */
export interface Customer {
  id: number;
  name: string;
  email?: string;
  phone?: string;
  address?: string;
  gstin?: string;
  createdAt: string;
  updatedAt: string;
  /** True only for the system-seeded Walk-in Customer — cannot be deleted or renamed. */
  isWalkin?: boolean;
}

/** Mirrors com.mtbs.business.customer.dto.CreateCustomerRequest. */
export interface CreateCustomerRequest {
  name: string;
  email?: string | null;
  phone?: string | null;
  address?: string | null;
  gstin?: string | null;
}

/** Mirrors com.mtbs.business.customer.dto.UpdateCustomerRequest — all fields optional. */
export type UpdateCustomerRequest = Partial<CreateCustomerRequest>;

/**
 * Client-side composition — CustomerResponse has no financial fields.
 * totalBills/lastTransactionAt come from one GET /business-invoices?customerId=
 * call; outstandingAmount from filtering GET /reports/outstanding by customerId.
 * null fields mean "not available" (e.g. current user lacks BILLING_MANAGE).
 */
export interface CustomerFinancialSummary {
  totalBills: number | null;
  lastTransactionAt: string | null;
  outstandingAmount: number | null;
}
