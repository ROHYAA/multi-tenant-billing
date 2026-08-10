import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/** Consistent "nothing here yet" placeholder for empty lists — <app-empty-state icon="people" title="No customers yet" message="Add your first customer to get started." /> */
@Component({
  selector: 'app-empty-state',
  imports: [MatIconModule],
  templateUrl: './empty-state.html',
  styleUrl: './empty-state.scss',
})
export class EmptyState {
  readonly icon = input<string>('inbox');
  readonly title = input.required<string>();
  readonly message = input<string>('');
}
