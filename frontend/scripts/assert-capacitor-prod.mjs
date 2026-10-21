import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(join(root, 'src/environments/environment.capacitor.prod.ts'), 'utf8');
const match = source.match(/apiBaseUrl:\s*'([^']+)'/);
const url = match?.[1] ?? '';

if (!url.startsWith('https://') || url.includes('REPLACE_WITH_YOUR_API_HOST')) {
  console.error(
    'Set src/environments/environment.capacitor.prod.ts apiBaseUrl to your public HTTPS Laravel API (https://your-host/api/v1), then rerun.',
  );
  process.exit(1);
}

console.log(`Capacitor production API: ${url}`);
