import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';

/**
 * Shared placeholder for every feature route not yet built (Phase 3.1 ships
 * the shell + routes/guards/sidebar links; each feature phase replaces its
 * one route entry with a real component — nothing else in the shell changes).
 * Route data drives the title/icon: { path: 'customers', data: { title: 'Customers', icon: 'people' } }
 */
@Component({
  selector: 'app-coming-soon',
  imports: [MatIconModule],
  templateUrl: './coming-soon.html',
  styleUrl: './coming-soon.scss',
})
export class ComingSoon {
  private readonly route = inject(ActivatedRoute);

  protected readonly title = toSignal(this.route.data.pipe(map((d) => (d['title'] as string) ?? 'Coming soon')));
  protected readonly icon = toSignal(this.route.data.pipe(map((d) => (d['icon'] as string) ?? 'construction')));
}
