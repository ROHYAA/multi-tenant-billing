import { Component, input } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/** Drop into any page/card while its own data is loading — <app-loading-indicator label="Loading customers…" /> */
@Component({
  selector: 'app-loading-indicator',
  imports: [MatProgressSpinnerModule],
  templateUrl: './loading-indicator.html',
  styleUrl: './loading-indicator.scss',
})
export class LoadingIndicator {
  readonly label = input<string>('Loading…');
  readonly diameter = input<number>(40);
}
