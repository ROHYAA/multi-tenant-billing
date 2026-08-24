import { Component, computed, contentChild, input, output, TemplateRef } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { PageResponse } from '../../../core/models/api-response.model';
import { EmptyState } from '../empty-state/empty-state';

export interface DataTableColumn<T> {
  key: string;
  header: string;
  cell: (row: T) => string;
  sortable?: boolean;
  align?: 'left' | 'right' | 'center';
  /** 'badge' renders cell() as a colored pill instead of plain text. */
  type?: 'text' | 'badge';
  /** Tailwind classes controlling the pill's color — only used when type is 'badge'. */
  badgeClass?: (row: T) => string;
}

/**
 * Generic list-screen table wrapping MatTable + MatPaginator + MatSort over
 * a PageResponse<T> — every later feature's list screen (Customers,
 * Products, Bills, ...) is column definitions + a service call, not
 * repeated table wiring. Cell rendering is a plain (row) => string function,
 * with an opt-in 'badge' presentation for status-style columns. A row-actions
 * column is optional — project a <ng-template #rowActions let-row> into the
 * component and it's appended as a fixed trailing column.
 */
@Component({
  selector: 'app-data-table',
  imports: [MatTableModule, MatPaginatorModule, MatSortModule, MatProgressBarModule, EmptyState, NgTemplateOutlet],
  templateUrl: './data-table.html',
  styleUrl: './data-table.scss',
})
export class DataTable<T> {
  readonly columns = input.required<DataTableColumn<T>[]>();
  readonly page = input<PageResponse<T> | null>(null);
  readonly loading = input(false);
  readonly emptyTitle = input('No records yet');
  readonly emptyMessage = input('');
  readonly pageSizeOptions = input<number[]>([10, 20, 50]);

  /** Optional trailing "Actions" column — <ng-template #rowActions let-row>...</ng-template> as content. */
  readonly rowActions = contentChild<TemplateRef<{ $implicit: T }>>('rowActions');

  /** Emits the new 0-based page index — matches Spring's Pageable convention. */
  readonly pageChange = output<number>();
  readonly sortChange = output<Sort>();
  readonly rowClick = output<T>();

  protected readonly displayedColumns = computed(() => {
    const keys = this.columns().map((c) => c.key);
    return this.rowActions() ? [...keys, 'actions'] : keys;
  });

  onPage(event: PageEvent): void {
    this.pageChange.emit(event.pageIndex);
  }

  onSort(sort: Sort): void {
    this.sortChange.emit(sort);
  }
}
