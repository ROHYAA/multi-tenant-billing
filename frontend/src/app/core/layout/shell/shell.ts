import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterOutlet } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { BreakpointObserver } from '@angular/cdk/layout';
import { map } from 'rxjs';
import { Sidebar } from '../sidebar/sidebar';
import { Topbar } from '../topbar/topbar';

// Matches Tailwind's `md` breakpoint (768px) — the plan's stated "collapses
// to an overlay drawer below md" behavior, driven by CDK rather than CSS
// alone since mat-sidenav's `mode` is a real @Input, not stylable via CSS.
const MOBILE_BREAKPOINT = '(max-width: 767.98px)';

/**
 * Authenticated app shell — fixed sidebar + topbar wrapping every feature
 * route. Desktop-first: sidenav is pinned open ('side' mode) by default;
 * below the mobile breakpoint it becomes an overlay drawer ('over' mode,
 * closed by default, toggled by Topbar's menu button).
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, MatSidenavModule, Sidebar, Topbar],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly breakpointObserver = inject(BreakpointObserver);

  protected readonly isMobile = toSignal(
    this.breakpointObserver.observe(MOBILE_BREAKPOINT).pipe(map((result) => result.matches)),
    { initialValue: false },
  );

  protected readonly mobileMenuOpen = signal(false);

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }
}
