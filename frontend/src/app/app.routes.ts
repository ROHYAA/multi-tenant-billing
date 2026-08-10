import { Routes } from '@angular/router';
import { Shell } from './core/layout/shell/shell';
import { authGuard } from './core/auth/auth-guard';
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
      // Real dashboard is a separate later phase (per Phase 2 approval).
      { path: 'dashboard', loadComponent: comingSoon, data: { title: 'Dashboard', icon: 'dashboard' } },
      {
        path: 'customers',
        loadComponent: comingSoon,
        canActivate: [permissionGuard],
        data: { title: 'Customers', icon: 'people', permission: 'CUSTOMER_MANAGE' },
      },
      {
        path: 'products',
        loadComponent: comingSoon,
        canActivate: [permissionGuard],
        data: { title: 'Products', icon: 'inventory_2', permission: 'PRODUCT_MANAGE' },
      },
      {
        path: 'bills',
        loadComponent: comingSoon,
        canActivate: [permissionGuard],
        data: { title: 'Bills', icon: 'receipt_long', permission: 'BILLING_MANAGE' },
      },
      {
        path: 'payments',
        loadComponent: comingSoon,
        canActivate: [permissionGuard],
        data: { title: 'Payments', icon: 'payments', permission: 'BILLING_MANAGE' },
      },
      {
        path: 'reports',
        loadComponent: comingSoon,
        canActivate: [permissionGuard],
        data: { title: 'Reports', icon: 'bar_chart', permission: 'BILLING_MANAGE' },
      },
      {
        path: 'shop-settings',
        loadComponent: comingSoon,
        canActivate: [permissionGuard],
        data: { title: 'Shop Settings', icon: 'settings', permission: 'TENANT_MANAGE' },
      },
    ],
  },

  { path: '**', component: NotFound },
];
