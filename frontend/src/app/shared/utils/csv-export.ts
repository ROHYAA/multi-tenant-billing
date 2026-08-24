/**
 * Client-side CSV export — builds a CSV from already-fetched data and
 * triggers a browser download. No backend endpoint needed: reports are
 * already fully loaded in the page by the time a user wants to export them,
 * so this just serializes what's on screen (respecting whatever date
 * range/year filter is currently applied).
 */
export function exportToCsv(filename: string, rows: Record<string, string | number>[]): void {
  if (rows.length === 0) return;

  const headers = Object.keys(rows[0]);
  const lines = [
    headers.join(','),
    ...rows.map((row) => headers.map((h) => escapeCsvCell(row[h])).join(',')),
  ];

  const blob = new Blob([lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename.endsWith('.csv') ? filename : `${filename}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

function escapeCsvCell(value: string | number | undefined): string {
  const str = String(value ?? '');
  return /[",\r\n]/.test(str) ? `"${str.replace(/"/g, '""')}"` : str;
}
