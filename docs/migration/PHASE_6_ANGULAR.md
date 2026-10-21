# Phase 6 — Angular environment (no production cutover)

Default `ng serve` / `environment.ts` still targets **Java** `http://localhost:8080/api/v1`. Rollback remains: use that URL.

## Local Laravel

1. Run Laravel on port 8000 (`php artisan serve`).
2. From `frontend/`: `npm run start:laravel`  
   Uses `environment.laravel.ts` (`apiBaseUrl: /api/v1`) and `proxy.laravel.json` so the browser talks to `:4200` and the CLI proxies `/api` to `:8000`. JWT cookies stay first-party.

Do not change production DNS or `environment.prod.ts` in this phase. Prod already uses same-origin `/api/v1`; swapping Java vs Laravel is a reverse-proxy/document-root choice in Phase 9.

## Optional: Laravel serves the SPA

```
npm run build:laravel
```

Copies `dist/frontend/browser` → `backend-laravel/public/spa/`. Laravel `GET /` (and HTML fallbacks) serve that `index.html` when present; otherwise the default welcome view. API routes stay `/api/v1/*`. `index.php` is never overwritten.

## Not in this phase

- Pointing live users at Laravel
- Capacitor (Phase 7)
- PDF 501 still applies for print/download against Laravel
- UI rewrites (business-type catalog 404 fallback remains so Java still works)
