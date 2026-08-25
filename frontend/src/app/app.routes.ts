import { Routes } from '@angular/router';
import { Shell } from './core/layout/shell/shell';
import { authGuard } from './core/auth/auth-guard';
import { adminGuard } from './core/auth/admin-guard';
import { permissionGuard } from './core/auth/permission-guard';
import { Forbidden } from './shared/pages/forbidden/forbidden';
import { NotFound } from './shared/pages/not-found/not-found';

const comingSoon = () => import('./shared/pages/coming-soon/coming-soon').then((m) => m.ComingSoon);

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: 'signup',
    loadComponent: () => import('./features/auth/signup/signup').then((m) => m.Signup),
  },
  { path: 'forbidden', component: Forbidden },

  // Platform-admin (SUPER_ADMIN) section — a separate auth system from the
  // tenant Shell below, so it lives outside it with its own guard.
  {
    path: 'admin/login',
    loadComponent: () => import('./features/admin/admin-login/admin-login').then((m) => m.AdminLogin),
  },
  {
    path: 'admin/tenants',
    canActivate: [adminGuard],
    loadComponent: () => import('./features/admin/admin-tenants/admin-tenants').then((m) => m.AdminTenants),
  },

  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      // Every child below is independently lazy-loaded — right now they all
      // resolve to the same ComingSoon placeholder, but the per-route
      // loadComponent wiring is what later phases replace one file at a
      // time (e.g. 'customers' -> features/customers/customers.ts) without
      // touching this file's structure or the shell/guards around it.
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard-page/dashboard-page').then((m) => m.DashboardPage),
        data: { title: 'Dashboard', icon: 'dashboard' },
      },
      {
        path: 'customers',
        canActivate: [permissionGuard],
        data: { title: 'Customers', icon: 'people', permission: 'CUSTOMER_MANAGE' },
        children: [
          {
            path: '',
            loadComponent: () => import('./features/customers/customer-list/customer-list').then((m) => m.CustomerList),
          },
          {
            path: ':id',
            loadComponent: () => import('./features/customers/customer-detail/customer-detail').then((m) => m.CustomerDetail),
          },
        ],
      },
      {
        path: 'products',
        loadComponent: () => import('./features/products/product-list/product-list').then((m) => m.ProductList),
        canActivate: [permissionGuard],
        data: { title: 'Products', icon: 'inventory_2', permission: 'PRODUCT_MANAGE' },
      },
      {
        path: 'bills',
        canActivate: [permissionGuard],
        data: { title: 'Bills', icon: 'receipt_long', permission: 'BILLING_MANAGE' },
        children: [
          {
            path: '',
            loadComponent: () => import('./features/billing/bill-list/bill-list').then((m) => m.BillList),
          },
          {
            path: 'new',
            loadComponent: () => import('./features/billing/bill-create/bill-create').then((m) => m.BillCreate),
          },
        ],
      },
      {
        path: 'payments',
        loadComponent: () => import('./features/payments/payments-page/payments-page').then((m) => m.PaymentsPage),
        canActivate: [permissionGuard],
        data: { title: 'Payments', icon: 'payments', permission: 'BILLING_MANAGE' },
      },
      {
        path: 'reports',
        loadComponent: () => import('./features/reports/reports-page/reports-page').then((m) => m.ReportsPage),
        canActivate: [permissionGuard],
        data: { title: 'Reports', icon: 'bar_chart', permission: 'BILLING_MANAGE' },
      },
      {
        path: 'shop-settings',
        loadComponent: () =>
          import('./features/settings/shop-settings-page/shop-settings-page').then((m) => m.ShopSettingsPage),
        canActivate: [permissionGuard],
        data: { title: 'Shop Settings', icon: 'settings', permission: 'TENANT_MANAGE' },
      },
    ],
  },

  { path: '**', component: NotFound },
];
