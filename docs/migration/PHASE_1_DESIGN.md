# PHASE 1 — Architecture and database design

**Status:** Design only. No Laravel project, no MySQL schema, and no Angular changes in this phase.  
**Date:** 2026-10-14  
**Depends on:** Phase 0 approval.  
**Isolation decision (approved):** shared MySQL database, shared tables, explicit `shop_id`, session-only tenant resolution.

The Spring Boot application and PostgreSQL source remain untouched.

---

## 1. Decisions locked for implementation (Phase 2+)

| Topic | Decision | Why |
|---|---|---|
| Tenant isolation | Shared MySQL DB + `shop_id` on every tenant-owned table | Typical PHP hosting cannot `CREATE DATABASE` per shop. PostgreSQL schema-per-tenant is **not** copied silently. |
| Tenant source of truth | Authenticated session / JWT claim only | Never trust body, query, path, or header `shopId` / `tenantId`. |
| Public API ids | New globally unique `BIGINT` `id` | Eloquent-friendly. Original PG `(schema, id)` stored as `source_id` for reconciliation only, **not** returned to Angular. |
| API contract | Keep `/api/v1`, envelope, cookie names, path names | Angular 20 stays as-is unless a listed exception is approved. |
| Browser auth | Compatible JWT HttpOnly cookies (`access_token`, `refresh_token`) | Sanctum’s CSRF cookie model would force Angular interceptor changes. |
| Android (later) | Same API + `Authorization: Bearer` (already accepted by Java) | Capacitor Phase 7; not a second backend. |
| Passwords | Verify existing BCrypt hashes; hash new passwords with bcrypt | Do not re-hash during data migration. |
| Money | `DECIMAL(12,2)` / `DECIMAL(10,2)`; no floats | Match Java `BigDecimal` HALF_UP scale 2. |
| Timestamps | Store UTC `DATETIME(6)`; JSON ISO-8601 Instant | Avoid MySQL `TIMESTAMP` session-TZ shifts. |
| Laravel location | Sibling directory `backend-laravel/` | Do not replace `src/` Java backend. |
| Legacy SaaS APIs | Out of scope | `legacy.saasbilling` and dropped PG tables are not rebuilt. |
| Business types | Keep current enum in schema; Phase 4 extends it | Do not remap RETAIL→grocery etc. in Phase 2. |
| Default admin | Not copied from `V6` seed | First SUPER_ADMIN created from env on empty DB only. |

**Still required before Phase 2 coding starts:** confirm the **target host PHP version and extensions** (not available from this workstation: `php` and `composer` are not installed; no host credentials in-repo).

---

## 2. PHP / Laravel / MySQL versions

### 2.1 Target (to verify on the PHP host)

| Component | Required | Preferred |
|---|---|---|
| PHP | **8.2.0+** | 8.3 |
| Extensions | `openssl`, `pdo`, `pdo_mysql`, `mbstring`, `tokenizer`, `xml`, `ctype`, `json`, `bcmath`, `fileinfo`, `curl` | `intl`, `gd` or `imagick` (PDF), `redis` |
| Composer | 2.x | 2.7+ |
| MySQL | **8.0.16+** (check constraints, `utf8mb4`) | 8.4 |
| Laravel | **12.x** if PHP ≥ 8.2 on host | Same |

If the host is PHP 8.1 or lower, **stop and report** — do not pin an unsupported Laravel. If the host is PHP 8.2 but missing `bcmath`, do not proceed until `bcmath` is enabled (money math).

**This Phase 1 did not inspect the production PHP host.** Local Windows PATH has no `php` / `composer`. Phase 2 first action: run `php -v`, `php -m`, and `mysql --version` on the actual server (or a matching staging box) and record results in `docs/migration/PHASE_2_FOUNDATION.md`.

### 2.2 Application timezone

- Database: UTC  
- Laravel `app.timezone`: `UTC`  
- Display default: shop `shop_settings.timezone` (seed `Asia/Kolkata`)  
- API timestamps: ISO-8601 with `Z` (match Java `Instant`)

### 2.3 Character set

`utf8mb4` / `utf8mb4_unicode_ci` for all tables (Marathi cash memo, names, addresses).

---

## 3. Tenant isolation design

### 3.1 What we are replacing

PostgreSQL: `public.shops.schema_name` → `SET search_path TO "schema_…", public`. Tenant tables had **no** `shop_id`. PK `1` collided across shops.

MySQL: one database (e.g. `shopledger`), every tenant row tagged `shop_id`.

`shops.schema_name` is migrated as a **legacy column** for support and cutover mapping. Laravel never uses it for routing.

### 3.2 Resolution pipeline (every request)

1. Read JWT from cookie `access_token`, else `Authorization: Bearer`.
2. Validate signature, expiry, issuer/audience.
3. If `isSuperAdmin`: no shop context; admin routes only.
4. Else set `ShopContext` from claims (`shop_id` / `tenantId`, `user_id`, `token_version`, `role`).
5. Load shop row; reject if missing/deleted.
6. If shop status is not `ACTIVE` and method is not GET/HEAD/OPTIONS and path is not `/api/v1/auth/*` → `403` `TENANT_PENDING_APPROVAL`.
7. Compare `token_version` to `users.token_version`.
8. Eloquent global scope `where shop_id = ShopContext::id()` on all tenant models.
9. Policies / middleware for `PERMISSION_*`.

Client-supplied `tenantId` / `shopId` / `schemaName` in JSON is **ignored**. Login still requires `tenantSlug` only to **choose which shop to authenticate**, then the JWT carries `tenantId`.

### 3.3 Enforcement layers (defense in depth)

| Layer | Rule |
|---|---|
| Middleware | JWT + shop status + token version |
| Eloquent | `BelongsToShop` trait: global scope + `creating` sets `shop_id` from context; `shop_id` not fillable |
| Policies | Resource `shop_id` must equal context (guards against `withoutGlobalScopes`) |
| Query builder | Ban raw SQL without bound `shop_id`; code review in Phase 8 |
| Unique indexes | Always include `shop_id` where uniqueness was per-schema |
| Files | Paths include `shop_id`; download looks up attachment by id **and** shop_id |
| Admin | SUPER_ADMIN may `ShopContext::run($shopId, fn)` for cross-shop reads; still audited |

### 3.4 Rejected alternatives

- **Database-per-tenant:** equivalent isolation, poor fit for shared hosting, Composer/migrate complexity, connection limits. Revisit only if the host documents unlimited DBs and the operator wants it.
- **MySQL views per shop:** extra objects, easy to skip in a report query.
- **Keep search_path emulation:** MySQL has no equivalent.

---

## 4. Laravel application structure

Create **`backend-laravel/`** at the repository root (next to `src/`, `frontend/`). Do not delete Java.

```
backend-laravel/
├── app/
│   ├── Http/
│   │   ├── Controllers/Api/V1/     # one controller per Java controller we keep
│   │   ├── Middleware/             # JwtCookieAuth, EnsureShopActive, EnsurePermission
│   │   ├── Requests/               # Form requests mirroring Java DTOs
│   │   └── Resources/              # JSON shapes (envelope applied globally)
│   ├── Models/
│   ├── Policies/
│   ├── Services/                   # BillCalculator, PaymentAllocator, NumberSeries, Pdf
│   ├── Support/                    # ShopContext, ApiResponse, Money
│   └── Jobs/                       # mail, expiry (optional)
├── database/migrations/
├── database/seeders/               # permissions, bill_templates — not live shop data
├── routes/api.php                  # prefix api/v1
├── storage/app/shops/{shop_id}/    # attachments
├── tests/Feature/                  # isolation, billing, auth
└── .env.example                    # no secrets
```

Java `src/` stays the production API until Phase 9 approval.

### 4.1 Module order (Phase 3)

Matches the approved brief: auth → shop onboarding/approval → admin → customers → products → bills/payments → settings/print config → reports → remainder (users/roles/attachments/outbox).

### 4.2 What is not in the Laravel app

- Razorpay platform subscriptions / webhooks  
- Quartz tables  
- React frontend  
- Hardcoded `admin@platform.com`

---

## 5. Authentication (browser + Android)

### 5.1 Browser (Angular 20 — no change planned)

Reproduce Java cookie behavior:

| Cookie | Path | HttpOnly | SameSite | Secure (prod) |
|---|---|---|---|---|
| `access_token` | `/` | yes | Lax | `APP_COOKIE_SECURE=true` |
| `refresh_token` | `/api/v1/auth/refresh` | yes | Lax | same |

- Access TTL: `JWT_EXPIRATION` (Java default 900000 ms = 15 min)  
- Refresh TTL: `JWT_REFRESH_EXPIRATION` (7 days)  
- Algorithm: HS256, secret from env (`JWT_SECRET`, 256-bit)  
- Body of login/signup/refresh: **no tokens** (`AuthResponse` nested `user` / `tenant` / `session` / `flags`)  
- `GET /auth/me`: flat `UserProfileResponse` (including `schemaName` as the migrated legacy value or empty string — Angular may ignore it; keep the field to avoid surprise)

Permissions in JSON remain **unprefixed** (`BILLING_MANAGE`), matching Angular `permissions.includes('BILLING_MANAGE')`.

Admin cookies: separate refresh path `/api/v1/admin/auth/refresh`, claim `isSuperAdmin=true`, no `tenantId`.

CORS: `CORS_ALLOWED_ORIGINS`, `supports_credentials=true`, methods GET/POST/PUT/PATCH/DELETE/OPTIONS.

CSRF: stay **disabled for this JWT-cookie API** to match current Angular (no XSRF header). Document residual CSRF risk: `SameSite=Lax` blocks most cross-site POSTs; Phase 8 re-evaluates if a custom domain + GET-based state change appears.

### 5.2 Android / Capacitor (Phase 7)

Java already accepts `Authorization: Bearer`. Laravel will too.

Capacitor should use HTTPS API URL from environment, store refresh token in the OS secure store (not Angular `localStorage` for the website). Cookie auth inside a WebView is fragile across origins; Bearer is the supported mobile path.

Do not claim offline billing.

### 5.3 Login rate limit

Java: 5 failures / 15 minutes / IP (Redis).  
Laravel: cache driver (`redis` if present, else `database`), same thresholds via env. Response `429` when blocked.

### 5.4 Password reset

Keep endpoints; tokens stored hashed in `password_reset_tokens` with `shop_id` + email, 15-minute TTL. No Angular screen yet.

---

## 6. API conventions

### 6.1 Envelope

Success and error JSON match `ApiResponse` / `PageResponse` (camelCase). Global exception handler maps:

| Case | HTTP | errorCode family |
|---|---|---|
| Validation | 400/422 — **use 400 with `fieldErrors`** if that is what Angular already handles | `VAL_*` |
| Unauthenticated | 401 | `UNAUTHORIZED` / `TOKEN_REVOKED` |
| Pending shop write | 403 | `TENANT_PENDING_APPROVAL` |
| Forbidden | 403 | `RES_*` / access denied |
| Not found | 404 | `RES_*` |
| Conflict (approve wrong status) | 409 | as Java |

**Check during Phase 2:** Java `GlobalExceptionHandler` status for `MethodArgumentNotValidException`. Laravel must match so `applyServerErrors` still works. If Java uses 400, Laravel uses 400, not default 422, unless Angular is updated (requires approval).

### 6.2 Pagination

Query: `page` (0-based, Spring style), `size`, `sort=createdAt,desc`.  
Do **not** switch to Laravel’s 1-based `?page=1` without an Angular change. Wrapper translates 0-based → SQL `OFFSET`.

### 6.3 Validation

Form requests cloned from Java DTO constraints (email, password min 8, GSTIN/PAN/IFSC patterns, quantity > 0, discount ≥ 0).

### 6.4 Breaking changes log

Any unavoidable difference goes in `docs/migration/BREAKING_API_CHANGES.md` (created when the first one exists). Phase 1 expected breaks: **none** for the Angular-used surface if the above is followed. Internal-only: `schemaName` may be empty string on new shops created in Laravel.

---

## 7. MySQL schema

Engine InnoDB, FK `ON DELETE RESTRICT` unless noted. Soft delete: `deleted` + `deleted_at` (keep Java’s flag; Laravel `SoftDeletes` can use `deleted_at` and we still set `deleted` for parity, or map SoftDeletes to `deleted_at` and treat `deleted` as generated — **implementation: both columns written together**).

Optimistic lock: `version` BIGINT, increment on update; optional `updated_at` check.

Audit: `created_at`, `updated_at`, `created_by`, `updated_by` (BIGINT, nullable).

### 7.1 Platform tables (no `shop_id`)

**shops**

- `id` BIGINT PK AI  
- `name`, `schema_name` VARCHAR(63) NULL UNIQUE (legacy), `slug` VARCHAR(63) UNIQUE, `owner_email`  
- `status` VARCHAR(50)  
- `plan_name`, `subscription_expires_at`, `expiry_alert_sent_at`  
- soft-delete + version + timestamps  

**permissions** — seed 10 names as today.

**platform_admins** — `email` UNIQUE, `password` bcrypt, `name`, `role`, `active`. Empty on migrate; seeder only if `ADMIN_EMAIL` + `ADMIN_PASSWORD` set.

**audit_logs** — same logical columns; JSON columns as MySQL `JSON`. `context_tenant_id` → keep name for API compatibility (`tenantId` in JSON).

**bill_templates** — seed `CASH_MEMO_V1`, `MARATHI_CASH_MEMO_V1`.

### 7.2 Tenant tables (all have `shop_id` FK → shops.id)

Indexes: `(shop_id, deleted)` plus today’s lookup indexes.

**roles** UNIQUE `(shop_id, name)`  
**users** UNIQUE `(shop_id, email)`; `token_version`; `role_id`  
**refresh_tokens** UNIQUE `token`; FK user  
**role_permissions** UNIQUE `(shop_id, role_id, permission_id)`  
**customers** — `is_walkin`; no unique email; one walk-in per shop via UNIQUE generated/partial: unique `(shop_id)` on walk-in using generated column `walkin_slot = IF(is_walkin AND deleted=0, shop_id, NULL)` UNIQUE  
**products**  
**bills** UNIQUE `(shop_id, invoice_number)`; money DECIMAL(12,2); status VARCHAR  
**bill_items** FK `(shop_id, bill_id)` or `bill_id` global PK; `product_id` nullable **no FK**  
**payments** DECIMAL amount; `status` ENUM/CHECK PENDING|CONFIRMED|CANCELLED; `payment_group_id` CHAR(36) NULL; `method` VARCHAR(50)  
**shop_settings** UNIQUE `(shop_id)` singleton  
**attachments**  
**number_series** unique active series: generated `active_type = IF(is_active, CONCAT(shop_id,'-',series_type), NULL)` UNIQUE  
**outbox_events** optional in Phase 2; can defer to Laravel `jobs` table  
**password_reset_tokens** new, shop-scoped  

**Do not create:** Quartz tables, old SaaS `invoices`/`subscriptions`/`usage_*`.

### 7.3 `source_id` columns

On every migrated tenant table: `source_id BIGINT NULL` after cutover unused for new rows. UNIQUE `(shop_id, source_id)` for idempotent import.

### 7.4 Billing rules (server, unchanged)

```
lineBase    = round(unitPrice * qty, 2, HALF_UP)
itemTax     = round(lineBase * taxPct / 100, 2, HALF_UP)
itemTotal   = round(lineBase + itemTax, 2)
subtotal    = Σ lineBase
taxAmount   = Σ itemTax
totalAmount = round(subtotal + taxAmount - discountAmount, 2)  # reject if < 0
```

Catalog `productId` snapshots name/price/tax.  
Bill create + item edits + finalize + payments + number allocation run in **DB transactions**.  
Payments: row lock bill (`lockForUpdate`). FIFO: lock all OPEN bills for customer oldest-first.  
CREDIT → PENDING; never marks PAID until confirm.  
Revenue reports: CONFIRMED only, filter `paid_at`.

Invoice number: lock `number_series` row, increment `current_number`, format `{prefix}-{FY}{seq:04d}`.

### 7.5 Business type (Phase 4 preview)

Column stays `VARCHAR(30)`. Current allowed values: `RETAIL|WHOLESALE|SERVICE|RESTAURANT|PHARMACY|OTHER`.  
Phase 4 adds values / config table **without** splitting databases. Industry modules (KOT, appointments, IMEI) stay future work.

---

## 8. Files and invoices

| Item | Design |
|---|---|
| Disk | `storage/app/shops/{shop_id}/{purpose}/{id}_{filename}` |
| Visibility | private; served only via authenticated `GET /attachments/{id}/file` |
| Max size | 2MB; PNG/JPEG only (reject WebP) |
| PDF | Generated on request (Java today). Laravel: DomPDF or Snappy **after** PHP `gd`/`intl` confirmed. Until PDF parity is tested, `/download` and `/preview` may be the last Java-compatible endpoints to switch. |
| Templates | Same codes + paper sizes; renderer registry `{code}:{paperSize}` |
| Historical PDF | `pdf_url` copied if present; otherwise reprint uses new renderer (visual drift accepted, documented) |

Do not rewrite Angular print (blob → new tab).

---

## 9. Environment configuration

`backend-laravel/.env.example` (committed). `.env` never committed.

```
APP_NAME=ShopLedger
APP_ENV=local
APP_DEBUG=false
APP_URL=
APP_COOKIE_SECURE=false
APP_FRONTEND_URL=http://localhost:4200

DB_CONNECTION=mysql
DB_HOST=
DB_PORT=3306
DB_DATABASE=shopledger
DB_USERNAME=
DB_PASSWORD=

CACHE_STORE=database          # redis when available
QUEUE_CONNECTION=sync         # database/redis when worker exists
SESSION_DRIVER=database

JWT_SECRET=
JWT_EXPIRATION=900000
JWT_REFRESH_EXPIRATION=604800000
JWT_ISSUER=
JWT_AUDIENCE=

CORS_ALLOWED_ORIGINS=http://localhost:4200

MAIL_HOST=
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM_ADDRESS=
MAIL_FROM_NAME=ShopLedger

ADMIN_EMAIL=                  # used only by optional seeder on empty DB
ADMIN_PASSWORD=

PG_SOURCE_URL=                # Phase 5 migration tool only; not the Laravel app
```

Razorpay vars omitted unless a ShopLedger feature still needs them (current POS does not).

---

## 10. Deployment architecture (design; Phase 9 writes the runbook)

```
[Browser Angular] --cookie JWT--> [HTTPS]
[Capacitor later] --Bearer JWT--> [HTTPS]
                    |
                    v
         PHP-FPM + nginx/Apache
         document root: backend-laravel/public
         /api/v1/* → Laravel
         / → optional: existing Java static OR Angular dist (Phase 6)
                    |
                    v
         MySQL 8  (shopledger)
         storage/app + storage/logs  (persistent, not web-root)
```

Until cutover:

- Java + Postgres remain live  
- Laravel + MySQL on a **separate** database and URL (e.g. `/laravel` or another host)  
- Angular `environment` still points at Java  

Scheduler: crontab `* * * * * php artisan schedule:run` if the host allows; otherwise expiry job is a documented manual/admin action.

Queues: default `sync` on cheap hosting; document worker if Redis/database queue is enabled.

Health: `GET /api/v1/health` (DB ping). Do **not** expose `/actuator` or open debug.

---

## 11. Backup and rollback

### 11.1 Before any MySQL write (Phase 5+)

- PostgreSQL: `pg_dump` snapshot (schema + data), stored off-host  
- MySQL: empty target; backups via `mysqldump` after each successful dry-run  
- Uploads: copy `STORAGE_LOCAL_PATH` tree with shop mapping manifest  

### 11.2 Rollback (Phase 9)

Rollback = **point Angular `apiBaseUrl` back at Java** and keep Postgres as source of truth. Laravel/MySQL can stay for analysis.

Rollback is invalid if new bills were written **only** to MySQL after cutover without a reverse-sync. Cutover plan (Phase 9) must include: maintenance window, disable Java writes or dual-write, migrate, verify, switch DNS/env, keep Java read-only for N days.

**Do not drop PostgreSQL schemas or Java deploy without explicit approval.**

### 11.3 Data migration tool (Phase 5, not now)

Repeatable artisan command, `--dry-run`, reads **snapshot** connection, writes MySQL, never `DELETE`/`UPDATE` on Postgres. Reconciliation report: counts, FK, Σ totals, outstanding, invoice uniqueness per shop, orphans.

---

## 12. Automated tests (required in Phase 2–8)

Minimum Feature tests:

- Login/signup/refresh/logout cookies  
- Pending shop cannot POST customers  
- Two shops: marker-string isolation on customers, products, bills, payments, settings, attachments  
- Bill math fixtures matching Java  
- CREDIT pending vs CONFIRMED revenue  
- Duplicate finalize / overpay rejected  
- Invoice number monotonic under concurrent `lockForUpdate` (where the test DB allows)  
- Permission matrix smoke  

Do not mark tests passed unless executed.

---

## 13. Phase 2 implementation boundaries

Phase 2 will:

1. Record host `php -v` / extensions (block if < 8.2)  
2. `composer create-project` Laravel in `backend-laravel/`  
3. MySQL connection + migrations for **all tables above** (empty)  
4. Envelope, auth cookies, ShopContext, rate limit, health  
5. Feature tests for auth + isolation skeleton  
6. `.env.example` only  

Phase 2 will **not** migrate production data, switch Angular, or delete Java.

---

## Phase 1 close-out

| # | Item | Value |
|---|---|---|
| 1 | Files created | `docs/migration/PHASE_1_DESIGN.md` |
| 2 | Functionality implemented | None |
| 3 | Commands executed | `php -v` / `composer -V` on the **dev workstation** — both not installed. Host PHP **not** inspected |
| 4 | Test results | Not run |
| 5 | Security concerns | Shared-table isolation is weaker than PG schemas; JWT-without-CSRF matches current app; default admin seed banned; actuator will not be copied |
| 6 | Known issues | Host PHP unknown; PDF library depends on `gd`/`intl`; 0-based `page` must be emulated; validation HTTP status must be confirmed against Java handler in Phase 2 |
| 7 | Manual verification | Review this file, especially §1 decisions and §7 schema |
| 8 | Remaining | Phase 2 Laravel foundation after approval |
| 9 | Approval needed? | **Yes. Stop here.** |

Please confirm Phase 2 (create `backend-laravel/`, MySQL migrations, auth skeleton) or request design changes (database-per-tenant, Sanctum CSRF, keep original per-shop ids as public `id`).
