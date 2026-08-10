import { Component, inject, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../auth/auth';
import { LoadingService } from '../../http/loading';
import { ThemeService } from '../../theme/theme';

@Component({
  selector: 'app-topbar',
  imports: [MatToolbarModule, MatIconModule, MatButtonModule, MatMenuModule, MatTooltipModule, MatProgressBarModule],
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar {
  protected readonly authService = inject(AuthService);
  protected readonly themeService = inject(ThemeService);
  protected readonly loadingService = inject(LoadingService);

  /** Emits when the mobile menu button is pressed — Shell owns the sidenav's open/close state. */
  readonly menuToggle = output<void>();
}
