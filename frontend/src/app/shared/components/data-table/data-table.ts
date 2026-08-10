import { Component, computed, input, output } from '@angular/core';
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
}

/**
 * Generic list-screen table wrapping MatTable + MatPaginator + MatSort over
 * a PageResponse<T> — every later feature's list screen (Customers,
 * Products, Bills, ...) is column definitions + a service call, not
 * repeated table wiring. Cell rendering is a plain (row) => string function
 * for v1 — custom cell templates can be added if a feature genuinely needs
 * richer cell content than text (e.g. a status chip), not built speculatively now.
 */
@Component({
  selector: 'app-data-table',
  imports: [MatTableModule, MatPaginatorModule, MatSortModule, MatProgressBarModule, EmptyState],
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

  /** Emits the new 0-based page index — matches Spring's Pageable convention. */
  readonly pageChange = output<number>();
  readonly sortChange = output<Sort>();
  readonly rowClick = output<T>();

  protected readonly displayedColumns = computed(() => this.columns().map((c) => c.key));

  onPage(event: PageEvent): void {
    this.pageChange.emit(event.pageIndex);
  }

  onSort(sort: Sort): void {
    this.sortChange.emit(sort);
  }
}
