import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(frontendRoot, 'dist', 'frontend', 'browser');
const dest = join(frontendRoot, '..', 'backend-laravel', 'public', 'spa');

if (!existsSync(src)) {
  console.error('Angular browser build not found at', src);
  process.exit(1);
}

rmSync(dest, { recursive: true, force: true });
mkdirSync(dest, { recursive: true });
cpSync(src, dest, { recursive: true });
console.log('Copied SPA to', dest);
