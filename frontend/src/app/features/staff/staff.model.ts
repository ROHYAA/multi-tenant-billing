export interface ShopUser {
  id: number;
  name: string;
  email: string;
  roleName: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateEmployeeRequest {
  name: string;
  email: string;
  password: string;
  roleName: 'EMPLOYEE';
}
