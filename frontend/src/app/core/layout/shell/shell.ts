import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterOutlet } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatSidenavModule } from '@angular/material/sidenav';
import { BreakpointObserver } from '@angular/cdk/layout';
import { Capacitor } from '@capacitor/core';
import { map } from 'rxjs';
import { AuthService } from '../../auth/auth';
import { Sidebar } from '../sidebar/sidebar';
import { Topbar } from '../topbar/topbar';

const MOBILE_BREAKPOINT = '(max-width: 767.98px)';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, MatIconModule, MatSidenavModule, Sidebar, Topbar],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly authService = inject(AuthService);

  protected readonly isMobile = toSignal(
    this.breakpointObserver.observe(MOBILE_BREAKPOINT).pipe(map((result) => result.matches)),
    { initialValue: false },
  );

  protected readonly mobileMenuOpen = signal(false);
  /** Android app: the native AdMob banner overlays the bottom of the WebView, so content needs room to scroll clear of it. */
  protected readonly nativeApp = Capacitor.isNativePlatform();

  protected readonly statusBanner = computed(() => {
    const user = this.authService.currentUser();
    switch (user?.tenantStatus) {
      case 'PENDING_APPROVAL':
        return {
          icon: 'hourglass_top',
          text: 'Your shop is pending approval. You can view data, but writes stay locked.',
        };
      case 'SUSPENDED':
        return {
          icon: 'block',
          text: 'Your shop has been suspended. You can view data, but billing writes stay locked.',
        };
      default:
        return null;
    }
  });

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }
}
