#!/usr/bin/env node
/**
 * LAN dev server for testing the Android app (phone or emulator) against this PC.
 *
 * Why: `php artisan serve` (PHP's built-in server) on Windows can silently drop bytes
 * when it writes to a non-loopback client (seen as corrupted PDFs on the phone).
 * Loopback is reliable, so PHP listens on 127.0.0.1 and this small Node proxy
 * serves the LAN on 0.0.0.0, streaming bytes through unchanged.
 *
 * Usage (from backend-laravel/):
 *   node scripts/lan-server.mjs                 # LAN :8000 -> PHP 127.0.0.1:8010
 *   node scripts/lan-server.mjs --port 8000 --inner-port 8010
 *   PHP_BINARY=C:\path\to\php.exe node scripts/lan-server.mjs
 *
 * Development only. Production uses nginx/Apache + PHP-FPM (docs/migration/PHASE_9_RUNBOOK.md).
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const port = Number(arg('--port', '8000'));
const innerPort = Number(arg('--inner-port', '8010'));
const php = arg('--php', process.env.PHP_BINARY || 'php');

const child = spawn(php, ['artisan', 'serve', '--host=127.0.0.1', `--port=${innerPort}`], {
  cwd: root,
  stdio: 'inherit',
});
child.on('exit', (code) => {
  console.error(`[lan-server] PHP server exited (${code}); stopping proxy.`);
  process.exit(code ?? 1);
});

const server = http.createServer((req, res) => {
  const upstream = http.request(
    {
      host: '127.0.0.1',
      port: innerPort,
      method: req.method,
      path: req.url,
      headers: {
        ...req.headers,
        'x-forwarded-for': req.socket.remoteAddress ?? '',
        'x-forwarded-proto': 'http',
      },
    },
    (upRes) => {
      res.writeHead(upRes.statusCode ?? 502, upRes.headers);
      upRes.pipe(res);
    },
  );
  upstream.on('error', (err) => {
    if (!res.headersSent) {
      res.writeHead(502, { 'content-type': 'application/json' });
    }
    res.end(JSON.stringify({ success: false, message: `PHP server not reachable: ${err.message}` }));
  });
  req.pipe(upstream);
});

server.listen(port, '0.0.0.0', () => {
  console.log(`[lan-server] LAN http://0.0.0.0:${port}  ->  PHP http://127.0.0.1:${innerPort}`);
});

const stop = () => {
  server.close();
  child.kill();
  process.exit(0);
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
