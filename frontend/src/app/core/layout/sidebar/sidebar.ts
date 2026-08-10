import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { AuthService } from '../../auth/auth';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Matches a backend PERMISSION_* authority (public.permissions) — omitted means always visible once authenticated. */
  permission?: string;
}

const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', icon: 'dashboard', route: '/dashboard' },
  { label: 'Customers', icon: 'people', route: '/customers', permission: 'CUSTOMER_MANAGE' },
  { label: 'Products', icon: 'inventory_2', route: '/products', permission: 'PRODUCT_MANAGE' },
  { label: 'Bills', icon: 'receipt_long', route: '/bills', permission: 'BILLING_MANAGE' },
  { label: 'Payments', icon: 'payments', route: '/payments', permission: 'BILLING_MANAGE' },
  { label: 'Reports', icon: 'bar_chart', route: '/reports', permission: 'BILLING_MANAGE' },
  { label: 'Shop Settings', icon: 'settings', route: '/shop-settings', permission: 'TENANT_MANAGE' },
];

@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, RouterLinkActive, MatIconModule, MatListModule],
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
})
export class Sidebar {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly navItems = computed(() => NAV_ITEMS.filter((item) => this.canSee(item)));

  logout(): void {
    this.authService.logout().subscribe(() => this.router.navigate(['/login']));
  }

  private canSee(item: NavItem): boolean {
    if (!item.permission) return true;
    return this.authService.currentUser()?.permissions.includes(item.permission) ?? false;
  }
}
