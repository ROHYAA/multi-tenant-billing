# Phase 8 — Tests and security

No production cutover.

## Fixes in this phase

- `BelongsToShop` always sets `shop_id` from the JWT session on create (client `shop_id` / `tenantId` cannot move a row).
- Permission catalog **POST/DELETE** require `super.admin` (tenant OWNER cannot mint global permissions).
- Shop settings update ignores `id` / `shop_id` / `tenantId`.

## CSRF (re-evaluation)

The API stays **CSRF-exempt** for Angular JWT cookies (`SameSite=Lax`, no GET that mutates shop data). Remaining risk: a same-site attack on a custom parent domain could POST with cookies. Capacitor uses Bearer, not cookies. Do not enable Sanctum CSRF until Angular sends an XSRF header (would be an approved UI change).

PDF `GET .../download` is 501 and does not change state.

## Query builder

Runtime services use Eloquent + `BelongsToShop`. `DB::table` appears only in the Phase 5 migrator (explicit `shop_id`) and health PDO ping. No unbounded raw SQL in request handlers.

## Tests added

Refresh/logout cookies; marker isolation (customers, products, bills, payments, settings); shop_id override; EMPLOYEE vs OWNER permission matrix; permission catalog locked; token_version revoke; login 429 lockout; duplicate finalize; overpay; monotonic `INV-0001` / `INV-0002`.

SQLite does not emulate MySQL `SELECT ... FOR UPDATE` concurrency; invoice uniqueness is asserted sequentially. Attachment HTTP is not implemented yet (no upload route); settings isolation covers the shop-owned row that logos would hang off.

## Not done

Phase 9 runbook, production deploy, host PHP inspection, PDF renderer.
