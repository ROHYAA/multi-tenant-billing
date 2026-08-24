/** Mirrors com.mtbs.business.product.dto.ProductResponse. */
export interface Product {
  id: number;
  name: string;
  description?: string;
  price: number;
  taxPercentage: number;
  hsnSacCode?: string;
  unit?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors com.mtbs.business.product.dto.CreateProductRequest. */
export interface CreateProductRequest {
  name: string;
  description?: string | null;
  price: number;
  taxPercentage?: number | null;
  hsnSacCode?: string | null;
  unit?: string | null;
}

/** Mirrors com.mtbs.business.product.dto.UpdateProductRequest — all fields optional. */
export type UpdateProductRequest = Partial<CreateProductRequest>;
