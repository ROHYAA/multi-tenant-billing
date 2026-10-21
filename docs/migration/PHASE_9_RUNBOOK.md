# Phase 9 — Cutover runbook

**Status: written only. This phase does not switch production traffic, DNS, or Angular env. Do not execute the window below without a separate, explicit go-ahead.**

Java + PostgreSQL remain the live system until that go-ahead. Do **not** drop Postgres schemas, delete the JAR/deploy, or point `environment.ts` / production `apiBaseUrl` at Laravel as part of this document existing.

---

## Current state (2026-10-14)

| Layer | Location | Notes |
|---|---|---|
| Java API (live) | `src/` Spring Boot, Postgres schema-per-tenant | Untouched |
| Laravel API | `backend-laravel/` Laravel 12, PHP 8.2+ | MySQL shared tables + `shop_id` |
| Angular 20 | `frontend/` | Default `environment.ts` → `http://localhost:8080/api/v1` (Java) |
| Laravel local | `npm run start:laravel` | Proxy to `http://127.0.0.1:8000` |
| Data copy | `php artisan shopledger:migrate-data --dry-run` | SELECT-only Postgres snapshot |
| Tests | `backend-laravel` PHPUnit | **40 passed / 247 assertions** on SQLite `:memory:` (not a substitute for MySQL on the host) |
| Production host PHP | **Never inspected** | Block cutover if host PHP &lt; 8.2 or missing extensions |

### Known gaps (do not claim done)

- Invoice PDF download/preview now returns `application/pdf` (Helvetica cash-memo; not pixel-identical iText). See `PHASE_PDF.md`.
- No attachment upload HTTP route; file copy is `--copy-files` + `PG_ATTACHMENTS_PATH` only.
- No scheduler jobs registered (`routes/console.php` is still the default inspire command). Shop expiry must stay on Java or be a documented admin action.
- Queue default is `sync`. No Redis required; login lockout uses cache (`database` or `file`).
- Capacitor is prep only (no APK).
- Outbox payload ids are not rewritten.
- Dual-write (Java + Laravel) is **not** implemented. Never split users across both APIs.

---

## Architecture after a future cutover

```
Browser  --cookie JWT, SameSite=Lax-->  HTTPS
Capacitor (later) --Bearer JWT-->       HTTPS
                                         |
                    PHP-FPM + nginx/Apache
                    document root: backend-laravel/public
                    /api/v1/*  → Laravel (index.php)
                    /          → public/spa/ (npm run build:laravel) or keep Java static until SPA is copied
                                         |
                    MySQL 8  database shopledger  (UTC DATETIME)
                    storage/app + storage/logs     (outside web root)
```

Do **not** expose `/actuator`, Swagger, `APP_DEBUG=true`, or open directory listings.

Until cutover, Laravel may run on a **separate host or path** with an empty or snapshot MySQL. Angular production stays on Java.

---

## 0. Pre-flight (must pass before any window)

Run on the **real** PHP host (not the workstation PHP at `C:\Users\rohit\tools\php83`):

```
php -v                          # 8.2 or 8.3
php -m                          # openssl, pdo_mysql, mbstring, tokenizer, xml, ctype, json, bcmath, curl, fileinfo
php artisan --version           # from backend-laravel after composer install
```

Also confirm:

- [ ] MySQL 8 empty **target** schema `shopledger` (not the Postgres database).
- [ ] `PG_SOURCE_URL` is a **read replica or `pg_dump` restore**, never Laravel `DB_*`.
- [ ] Off-host `pg_dump` of production Postgres (schemas + data).
- [ ] Copy of Java `STORAGE_LOCAL_PATH` (logos/signatures).
- [ ] `.env` exists only on the server; not in git. `APP_KEY` and `JWT_SECRET` (≥32 chars) set. `APP_DEBUG=false`, `APP_ENV=production`, `APP_COOKIE_SECURE=true` on HTTPS.
- [ ] `CORS_ALLOWED_ORIGINS` is the real SPA origin (exact). Same-origin `/api/v1` needs no extra CORS for the browser.
- [ ] First platform admin via `ADMIN_EMAIL` / `ADMIN_PASSWORD` seeder **or** a rotated password — never copy stock `admin@platform.com`.
- [ ] `php artisan migrate --force` on empty MySQL, then `php artisan db:seed` (permissions, bill templates, business types).
- [ ] `GET /api/v1/health` → `data.status = UP`.
- [ ] PHPUnit on this tree: `php vendor/bin/phpunit` in `backend-laravel/` — do not mark passed unless executed.
- [ ] Print a sample bill on staging (`GET .../download`) and accept Helvetica layout vs Java iText.

If any box fails, **stop**. Do not copy data or switch the SPA.

---

## 1. Parallel stack (safe anytime)

1. Deploy Laravel next to Java (second vhost or `/laravel` path).
2. Point that vhost at `backend-laravel/public`.
3. Use a **copy** of MySQL, not production Postgres.
4. Operators only: `npm run start:laravel` locally, or a staging SPA with `environment.laravel.ts`.
5. Production Angular remains on Java.

---

## 2. Maintenance window (only after explicit approval)

Estimated: freeze writes long enough for dump + dry-run + live copy + reconcile + smoke. Do not dual-write.

### 2.1 Freeze Java writes

- Put the shop in maintenance (or stop the Java process / block POST/PUT/PATCH at the proxy).
- Confirm no new bills/payments: watch Postgres or Java logs.

### 2.2 Snapshot

```
pg_dump --format=custom --file=shopledger-pg-$(date +%Y%m%d).dump ...
mysqldump shopledger > shopledger-mysql-precopy.sql   # should be schema+seeds only
```

Restore Postgres dump to a snapshot DB if `PG_SOURCE_URL` must not hit primary.

### 2.3 Dry-run copy

```
cd backend-laravel
php artisan shopledger:migrate-data --dry-run --report=storage/logs/dry-run.json
```

Exit 0 and empty `discrepancies` required. Investigate orphans, bill/payment totals, invoice uniqueness, `number_series.current_number`. Repeat until clean. Postgres is not written.

### 2.4 Live copy into MySQL

Target must still have **no shops** (or use `--allow-existing` only for an idempotent retry of the same copy).

```
php artisan shopledger:migrate-data --report=storage/logs/copy.json
# optional, after paths are verified:
php artisan shopledger:migrate-data --allow-existing --copy-files
```

`--include-admins` only after rotating default platform passwords.

If the report is not `ok: true`, **do not switch Angular**. Leave Java frozen or unfreeze Java and abort (MySQL copy can be dropped and retried; Postgres is still source of truth).

### 2.5 Switch the SPA (same-origin)

Production `environment.prod.ts` already uses `apiBaseUrl: '/api/v1'`. Cutover is **proxy document root**, not an Angular code change:

- Serve `npm run build:laravel` output from `backend-laravel/public/spa/`.
- Nginx/Apache: `/api/v1` and `/up` → `index.php`; everything else → SPA `index.html` if the file exists.
- `APP_URL` = public HTTPS origin. `APP_COOKIE_SECURE=true`.

Do **not** change default `frontend/src/environments/environment.ts` (Java) until you intend all local `ng serve` to hit Laravel.

Example nginx (illustrative, not applied):

```
root /var/www/shopledger/backend-laravel/public;
location /api/ { try_files $uri $uri/ /index.php?$query_string; }
location /up { try_files $uri /index.php?$query_string; }
location / {
    try_files /spa/$uri /spa/index.html /index.php?$query_string;
}
```

### 2.6 Smoke (same window)

- [ ] Login (cookie) as one migrated owner; `/auth/me` shop id matches old tenant id.
- [ ] Second shop: cannot GET first shop’s customer/bill ids (404).
- [ ] Create a **test** bill after cutover; invoice number continues from migrated `current_number` (no reuse of last Java number).
- [ ] CREDIT payment stays PENDING until confirm; CASH is CONFIRMED.
- [ ] Reports outstanding/revenue for a known shop match the dry-run money section (pre-cutover bills).
- [ ] Admin login (new or rotated user).
- [ ] Print/download: PDF bytes, filename `{invoiceNumber}.pdf`; other shop’s id is 404.

Keep Java **read-only** (or stopped writes) for N days. Do not delete it.

### 2.7 After the window

```
* * * * * php artisan schedule:run
```

No ShopLedger schedules are registered yet; the crontab is for future expiry/outbox jobs. Until then, expiry remains a Java or manual admin action.

`QUEUE_CONNECTION=sync` unless a worker is actually running.

---

## 3. Rollback (valid only if MySQL is not the sole writer of new bills)

1. Point the reverse proxy / SPA back at the **Java** process (same `/api/v1`).
2. Unfreeze Java writes.
3. Postgres is source of truth again.
4. Leave Laravel/MySQL up for forensics; do not `DROP DATABASE`.

**Rollback is invalid** if any shop created bills/payments **only** on MySQL after switch. Then you must either keep Laravel as leader or build a reverse sync (not provided).

---

## 4. Forbidden without a further written approval

- `DROP SCHEMA` / drop Postgres / delete Flyway history
- Undeploy or delete the Java tree/`src/`
- Force-push or `--no-verify` around secrets
- Committing `.env`, `JWT_SECRET`, `ADMIN_PASSWORD`, `PG_SOURCE_URL` with credentials
- Running `shopledger:migrate-data` with Laravel `DB_*` pointed at Postgres
- `DELETE`/`UPDATE` on the Postgres snapshot from this tool (the reader refuses non-SELECT)

---

## 5. Local commands (not production)

```
# API
cd backend-laravel
cp .env.example .env
php artisan key:generate
# set JWT_SECRET, DB_*, never commit .env
php artisan migrate --seed
php artisan serve

# SPA vs Laravel (Java remains default ng serve)
cd frontend
npm run start:laravel

# tests (must be run to claim pass)
cd backend-laravel
php vendor/bin/phpunit
```

---

## 6. Phase index

| Phase | Doc | Done? |
|---|---|---|
| 0 Audit | `PHASE_0_AUDIT.md`, `API_INVENTORY.md`, `DATABASE_MAPPING.md`, `MIGRATION_RISKS.md` | Yes |
| 1 Design | `PHASE_1_DESIGN.md` | Yes |
| 2 Foundation | `PHASE_2_FOUNDATION.md` | Yes |
| 3 Modules | `PHASE_3_MODULES_1_3.md`, `PHASE_3_MODULES_4_9.md` | Yes |
| 4 Business types | `PHASE_4_BUSINESS_TYPES.md` | Yes |
| 5 Data copy | `PHASE_5_DATA_MIGRATION.md` | Command + tests; no prod copy |
| 6 Angular env | `PHASE_6_ANGULAR.md` | Laravel config exists; default still Java |
| 7 Capacitor | `PHASE_7_CAPACITOR.md` | Prep only |
| 8 Tests/security | `PHASE_8_SECURITY.md` | Yes (PHPUnit) |
| 9 Runbook | this file | **Documented; cutover not executed** |
