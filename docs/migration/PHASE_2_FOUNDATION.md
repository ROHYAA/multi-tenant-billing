# PHASE 2 — Laravel API foundation

**Date:** 2026-10-14  
**Location:** `backend-laravel/` (Java `src/` untouched)

## PHP actually used

| Where | Result |
|---|---|
| Target hosting PHP | **Not inspected** (no host access) |
| This workstation | PHP was not on PATH. Installed **PHP 8.3.35 NTS** locally at `C:\Users\rohit\tools\php83` for development only |
| Composer | 2.10.3 |
| Laravel | **12.70** (`laravel/laravel` v12.12.2) — PHP `^8.2` |
| Test DB | SQLite `:memory:` (phpunit). App `.env.example` defaults to **MySQL** |

If the production host is below PHP 8.2, stop and report before deploying this tree.

## What was implemented

- MySQL-oriented schema migrations (platform + tenant tables with `shop_id` and `source_id`)
- `ApiResponse` envelope matching Java (`success`, `data`, `errorCode`, `fieldErrors`, `timestamp`)
- Validation failures: **HTTP 400** + `VAL_6001` (Java-compatible, not Laravel’s default 422)
- JWT HS256 cookies `access_token` / `refresh_token` (SameSite=Lax, HttpOnly); Bearer header also accepted
- Signup → shop `PENDING_APPROVAL`, OWNER user, roles/permissions, walk-in customer, shop_settings, invoice number series
- Login / refresh / logout / me
- Admin login / me / logout (no default admin seed)
- Login failure rate limiter (cache; 5 / 15 min, raised in tests)
- `ShopContext` + Eloquent `BelongsToShop` global scope (deny-all if no shop)
- Write block for non-ACTIVE shops (`TENANT_PENDING_APPROVAL`)
- Audit log row on signup/login
- Health: `GET /api/v1/health`
- Feature tests (executed): 10 passed

## Stub (replace in Phase 3)

`POST /api/v1/customers` is a placeholder used to prove the pending-write guard. ACTIVE shops currently get **501**. Angular POS does not call this until the shop is approved; still a contract gap until CustomerController exists.

Admin `POST /api/v1/admin/auth/refresh` is **not** implemented yet.

## Tests executed

```
cd backend-laravel
php artisan test
```

Result: **10 passed** (32 assertions). SQLite in-memory, not MySQL.

## Security notes

- `.env` is gitignored; `.env.example` has no secrets
- No seeded `admin@platform.com`
- JWT cookies excluded from Laravel cookie encryption so Angular can send them as raw JWTs
- CSRF disabled on `api/*` to match current Angular
- Tenant isolation is application-layer; tests cover two shops on `customers` via global scope

## How to run locally

1. PHP 8.2+ with `openssl`, `pdo_mysql` or `pdo_sqlite`, `mbstring`, `bcmath`, `curl`
2. Copy `.env.example` → `.env`, set `APP_KEY` (`php artisan key:generate`), `JWT_SECRET` (≥32 chars or Java-style base64), MySQL credentials
3. `php artisan migrate --seed`
4. `php artisan serve` (API at `http://localhost:8000/api/v1`)
5. Do **not** point production Angular at this API yet
