import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';

export interface Breadcrumb {
  label: string;
  link?: string;
}

/**
 * Title + breadcrumb trail + right-aligned action slot, consistently placed
 * atop every feature list/detail page — <app-page-header [title]="..."
 * [breadcrumbs]="[...]"><button mat-flat-button>Add Customer</button></app-page-header>
 */
@Component({
  selector: 'app-page-header',
  imports: [RouterLink, MatIconModule],
  templateUrl: './page-header.html',
  styleUrl: './page-header.scss',
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly breadcrumbs = input<Breadcrumb[]>([]);
}
