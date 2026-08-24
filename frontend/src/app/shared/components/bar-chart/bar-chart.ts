import { Component, computed, input } from '@angular/core';

export interface BarChartPoint {
  label: string;
  value: number;
}

/**
 * Single-series magnitude-over-time bar chart — e.g. "revenue collected per
 * month". Deliberately single-series: no legend needed (the chart title
 * names the series), which keeps this out of the multi-hue/ΔE-validation
 * territory a categorical chart would need. Bars use the app's own themed
 * Material primary token rather than a new custom color, since that token
 * already carries Material's own contrast guarantees.
 *
 * Hover uses a native <title> tooltip — simple and fully accessible, not
 * the richest possible UI, but proportionate for a secondary chart inside
 * a larger Reports/Dashboard build.
 */
@Component({
  selector: 'app-bar-chart',
  templateUrl: './bar-chart.html',
  styleUrl: './bar-chart.scss',
})
export class BarChart {
  readonly data = input.required<BarChartPoint[]>();
  readonly valueFormatter = input<(value: number) => string>((v) => v.toLocaleString('en-IN'));
  readonly height = input(160);

  protected readonly maxValue = computed(() => Math.max(1, ...this.data().map((d) => d.value)));

  protected readonly bars = computed(() => {
    const max = this.maxValue();
    return this.data().map((d) => ({
      ...d,
      heightPercent: Math.max(2, Math.round((d.value / max) * 100)),
      formatted: this.valueFormatter()(d.value),
    }));
  });
}
