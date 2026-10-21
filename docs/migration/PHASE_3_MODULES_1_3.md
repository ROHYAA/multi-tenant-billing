# PHASE 3 modules 1–3 — Auth remaining, shop self-service, admin APIs

**Date:** 2026-10-14  
**Scope:** Laravel `backend-laravel/` only. Java `src/` untouched. Angular unchanged.

## Implemented

### Auth (module 1)

- `POST /api/v1/auth/tenants` — public; always 200; empty list if email unknown (anti-enumeration)
- `POST /api/v1/auth/forgot-password` — unknown slug `TNT_2001` 404; unknown email 200
- `POST /api/v1/auth/reset-password` — hashed token in `password_reset_tokens`, 15-minute TTL, `AUTH_1007` if invalid
- `POST /api/v1/admin/auth/refresh` — stores refresh UUID in cache (`super_admin_refresh:{token}`), rotates on use
- Shop suspended login error code **`TNT_2004`** (was incorrectly TNT_2003)

### Shop self-service (module 2)

- `GET/PUT /api/v1/tenant`
- `GET /api/v1/tenant/schema` and `/status`
- `POST /api/v1/tenant/deactivate` (OWNER + `TENANT_MANAGE`; pending shops still write-blocked)
- `POST /api/v1/tenant/reactivate` (`TENANT_MANAGE`)

### Admin (module 3)

- `GET /api/v1/admin/tenants` (+ `status` filter, 0-based `page`/`size`/`sort`)
- `GET /api/v1/admin/tenants/{id}`
- `PUT /api/v1/admin/tenants/{id}/status`
- `POST /api/v1/admin/tenants/{id}/approve` — `PENDING_APPROVAL` → `ACTIVE` + plan; else **409 TNT_2009**
- `POST /api/v1/admin/tenants/{id}/reactivate` — `SUSPENDED` → `ACTIVE` + plan; else **409 TNT_2010**
- `GET /api/v1/admin/tenants/{id}/users` (without shop global scope; filter by `shop_id`)
- `DELETE /api/v1/admin/tenants/{id}`
- `DELETE /api/v1/admin/users/tenant/{tenantId}/user/{userId}`
- `GET /api/v1/admin/audit-logs` (+ user/entity/tenant/date-range/statistics)

`EnsurePermission` middleware aliases `permission:{NAME}`. Super admin bypasses it.

## Not in this slice

Customers, products, bills, payments, reports, shop settings, templates, attachments, tenant user/role CRUD (Phase 3 modules 4–9).

`POST /api/v1/customers` remains a 501 stub for ACTIVE shops (pending shops still 403 `TENANT_PENDING_APPROVAL`).

No default platform admin is seeded.

## Tests

```
cd backend-laravel
php artisan test
```

Result: **20 passed** (77 assertions). SQLite `:memory:`, not MySQL.
