# Phase 5 — PostgreSQL → MySQL data copy

Status: **implemented, dry-run first**. Not a production cutover.

## Command

```
php artisan shopledger:migrate-data --dry-run
php artisan shopledger:migrate-data --shop=alpha --dry-run
php artisan shopledger:migrate-data --allow-existing
php artisan shopledger:migrate-data --include-admins --copy-files
```

- Source: `PG_SOURCE_URL` (pgsql, **SELECT only**). Never UPDATE/DELETE Postgres.
- Target: Laravel `DB_CONNECTION` (MySQL in production). Separate database from the snapshot.
- Report JSON: `storage/logs/migration-report-*.json` or `--report=`.
- Exit 1 if unexplained discrepancies remain.

## Mapping

| Rule | Behavior |
|---|---|
| Shop ids | Preserved (`shops.id` from `public.shops`) |
| Tenant PKs | New global BIGINT; original id stored as `source_id`; unique `(shop_id, source_id)` |
| Tenant FKs | Remapped via `(shop_id, source_id)` |
| `bill_items.invoice_id` / `payments.invoice_id` | Laravel `bill_id` |
| Permissions | Match by `name` (no duplicate seed rows) |
| Bill templates | Match by `code` |
| Passwords | Copied as bcrypt via query builder (not Eloquent hashed cast) |
| Timestamps | Parsed to UTC `DATETIME` |
| `number_series.current_number` | Copied verbatim |
| Invoice numbers | Unchanged; uniqueness is per `shop_id` |
| Attachments | DB `storage_key` rewritten to `shops/{shop_id}/{purpose}/{source_id}_{file}`; bytes only with `--copy-files` |

## Not copied

- Quartz `QRTZ_*`
- `plans`, subscriptions, Razorpay platform SaaS
- Default `admin@platform.com` (even with `--include-admins`)
- Outbox payload inner ids (payload copied as-is; warning in report)

## Safety

- `--dry-run` writes nothing to the target (except the JSON report file).
- Live copy refuses if the target already has shops unless `--allow-existing` (idempotent skip on `(shop_id, source_id)`).
- Retries do not UPDATE existing financial rows.
- Do not run against the live Postgres as the Laravel `DB_*` target.

## Tests

`tests/Feature/DataMigrationTest.php` uses a second sqlite connection as the snapshot (two shops, colliding customer/bill `id = 1`). PHPUnit does not require `pdo_pgsql`.

## Host cutover

Not this phase. Point `PG_SOURCE_URL` at a **read replica or snapshot**, run `--dry-run`, fix discrepancies, then a write copy into an empty MySQL schema — only after Phase 9 approval.
