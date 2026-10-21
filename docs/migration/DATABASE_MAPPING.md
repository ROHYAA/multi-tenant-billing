# Database mapping — PostgreSQL (source) → MySQL (target)

**Source isolation:** one PostgreSQL database, `public` schema + **one schema per shop**.  
**Hibernate:** `multiTenancy: SCHEMA` + `SET search_path`.  
**Tenant business tables have no `shop_id` today.**

Flyway:

- Public: `src/main/resources/db/migration/public/V1`–`V12`
- Tenant (replayed per schema): `.../tenant/V1`–`V28`

Do not edit or run these against production as part of Phase 0.

---

## 1. How to read a live shop’s data

```sql
-- Shops (public)
SELECT id, name, schema_name, slug, status, owner_email
FROM public.shops WHERE deleted = false;

-- Then, for each schema_name:
SET search_path TO "schema_xxx_123", public;
SELECT * FROM customers WHERE deleted = false;
```

Schema provisioning: `TenantFlywayMigrationService` `CREATE SCHEMA IF NOT EXISTS` then Flyway `schemas(schemaName)`.

---

## 2. PostgreSQL → MySQL type map (deliberate)

| PostgreSQL | Used for | MySQL recommendation | Notes |
|---|---|---|---|
| `BIGSERIAL` | All PKs | `BIGINT NOT NULL AUTO_INCREMENT` | Preserve values on import (`SET SESSION sql_mode` / insert with id) |
| `BIGINT` | FKs, version | `BIGINT` | |
| `BOOLEAN` | deleted, flags | `TINYINT(1)` | |
| `VARCHAR(n)` | strings | `VARCHAR(n)` charset `utf8mb4` | |
| `TEXT` | notes, address | `TEXT` | |
| `NUMERIC(12,2)` | money | `DECIMAL(12,2)` | Never `FLOAT`/`DOUBLE` |
| `NUMERIC(10,2)` | quantity | `DECIMAL(10,2)` | |
| `NUMERIC(5,2)` | tax % | `DECIMAL(5,2)` | |
| `SMALLINT` | precision, margins | `SMALLINT` | |
| `TIMESTAMPTZ` | created_at, paid_at, due_date | `DATETIME(6)` stored **UTC** *or* `TIMESTAMP` | App today is Instant/UTC. Pick one and convert all rows. `TIMESTAMP` in MySQL is timezone-session sensitive — prefer UTC `DATETIME(6)` + app TZ `Asia/Kolkata` for display. |
| `JSONB` | audit_logs changes_* | `JSON` | GIN indexes → optional generated columns / `JSON` path indexes (MySQL 8.0.17+) |
| `UUID` | `payments.payment_group_id` | `CHAR(36)` or `BINARY(16)` | CHAR(36) is simpler for Laravel |
| `BYTEA` / files | not in DB | filesystem / object storage | `attachments.storage_key` is a path |

Partial indexes (`WHERE deleted = false`) have no MySQL equivalent; use ordinary indexes (or generated stored columns). Unique partial index `number_series(series_type) WHERE is_active` → unique `(shop_id, series_type)` **plus** application rule, or a generated column `active_type` null when inactive.

`ON CONFLICT DO NOTHING` seeds → `INSERT IGNORE` / `INSERT ... ON DUPLICATE KEY UPDATE`.

No PostgreSQL `search_path`. Isolation must be a column + ORM global scope.

---

## 3. Public schema — current tables

Dropped later (do not migrate): `plans`, `plan_pricing`, `plan_features`, `plan_limits`, `tenant_onboarding` (V9). `tenants` renamed to `shops`.

### 3.1 `shops` (V1 as `tenants` + V9 rename + V12)

| Column | PG type | Meaning |
|---|---|---|
| id | BIGSERIAL PK | Shop / tenant id (JWT `tenantId`) |
| deleted, deleted_at, version | standard audit | Soft delete |
| created_at, updated_at, created_by, updated_by | TIMESTAMPTZ / BIGINT | |
| name | VARCHAR(255) | Shop display name (signup `name`) |
| schema_name | VARCHAR(63) UNIQUE | PG schema; **MySQL: keep as legacy column or drop after cutover** |
| status | VARCHAR(50) | ACTIVE / INACTIVE / SUSPENDED / REGISTERED / PENDING_APPROVAL |
| owner_email | VARCHAR(255) | Unique-in-practice via app check |
| slug | VARCHAR(63) UNIQUE | Login `tenantSlug` |
| plan_name | VARCHAR(100) | Offline plan label |
| subscription_expires_at | TIMESTAMPTZ | |
| expiry_alert_sent_at | TIMESTAMPTZ | |

**MySQL extra:** none required for identity; `schema_name` becomes informational.

### 3.2 `permissions` (V2 + V4 seed)

`id`, audit columns, `name` UNIQUE, `description`.  
Names: TENANT_VIEW, TENANT_MANAGE, USER_VIEW, USER_MANAGE, USER_DELETE, ROLE_VIEW, ROLE_MANAGE, BILLING_MANAGE, CUSTOMER_MANAGE, PRODUCT_MANAGE.

### 3.3 `platform_admins` (V6)

`id`, `email` UNIQUE, `password` BCrypt, `name`, `role` default SUPER_ADMIN, `active`, `created_at`.  
**Do not copy the seeded default user into a new environment without rotating the password.** Prefer env-created first admin.

### 3.4 `audit_logs` (V8)

Platform audit. JSONB columns: `changes_before`, `changes_after`, `changes_summary`, `context_metadata`.  
`created_by`/`updated_by` here are VARCHAR (inconsistent with BIGINT elsewhere).

### 3.5 `bill_templates` (V10 + V11)

`code` UNIQUE, `name`, `description`, `is_active`.  
Seeds: `CASH_MEMO_V1`, `MARATHI_CASH_MEMO_V1`.  
Referenced by `shop_settings.bill_template_id` (logical FK, not enforced).

---

## 4. Tenant schema — current tables (repeat per shop)

Dropped by V20 (do not migrate if empty/absent): old `payments`, `invoice_line_items`, `invoices`, `usage_*`, `subscriptions`.  
Renames V21: `business_invoices`→`bills`, `business_invoice_items`→`bill_items`, `business_payments`→`payments`.

**MySQL shared-table proposal:** add `shop_id BIGINT NOT NULL` (FK to `shops.id`) on every table below except when the table is global (`permissions`, `bill_templates`, `platform_admins`, `audit_logs`, `shops`). Unique constraints expand to include `shop_id`.

### 4.1 `roles` (V1 + V12 seed)

`name` UNIQUE per schema → UNIQUE(`shop_id`,`name`). Seeds: OWNER, ADMIN, EMPLOYEE.

### 4.2 `users` (V2)

| Column | Notes |
|---|---|
| email | UNIQUE per schema → UNIQUE(`shop_id`,`email`) |
| password | BCrypt — **preserve hashes**; Laravel must use bcrypt compatible `$2a$`/`$2y$` |
| role_id | FK roles |
| status | VARCHAR |
| token_version | BIGINT default 1 |
| is_first_login | BOOLEAN |

### 4.3 `refresh_tokens` (V3)

`token` UNIQUE, `user_id` FK, `expiry_date`, `revoked`.

### 4.4 `role_permissions` (V4 + V13 seed)

`role_id`, `permission_id` (logical FK to **public.permissions**). No cross-schema PG FK.

### 4.5 Quartz `QRTZ_*` (V11)

Still created in every tenant schema. Platform Quartz in YAML is one scheduler — this duplication is leftover. **Recommend not migrating Quartz tables**; use Laravel scheduler / database queue instead.

### 4.6 `customers` (V14 + V21 drop razorpay + V26 walk-in)

| Column | Type | Notes |
|---|---|---|
| name | VARCHAR(255) | Walk-in seeded name |
| email, phone | nullable | email not unique |
| address | TEXT | |
| gstin | VARCHAR(20) | |
| is_walkin | BOOLEAN | exactly one per shop |

### 4.7 `products` (V15)

`price NUMERIC(12,2)`, `tax_percentage NUMERIC(5,2)`, `hsn_sac_code`, `unit`, `is_active`.

### 4.8 `bills` (V16 as business_invoices + V21 + V26 discount)

| Column | Type | Notes |
|---|---|---|
| invoice_number | VARCHAR(50) UNIQUE | per schema; MySQL UNIQUE(`shop_id`,`invoice_number`) |
| customer_id | FK customers | |
| status | VARCHAR(30) | DRAFT/OPEN/PAID/VOID/UNCOLLECTIBLE |
| subtotal, tax_amount, total_amount | NUMERIC(12,2) | stored |
| discount_amount | NUMERIC(12,2) default 0 | bill-level |
| currency | CHAR/VARCHAR(3) default INR | |
| notes | TEXT | |
| due_date, paid_at | TIMESTAMPTZ | |
| pdf_url | VARCHAR(500) | often unused if PDF generated on the fly |

### 4.9 `bill_items` (V17)

`invoice_id` FK bills, `product_id` **nullable no FK**, description, quantity `NUMERIC(10,2)`, unit_price, tax_percentage, tax_amount, total.

### 4.10 `payments` (V18 + V21 + V28)

| Column | Type | Notes |
|---|---|---|
| invoice_id | FK bills | |
| amount | NUMERIC(12,2) | |
| currency | VARCHAR(3) | |
| method | VARCHAR(50) | CASH/CARD/UPI/NETBANKING/BANK_TRANSFER/CREDIT |
| status | VARCHAR(20) | PENDING/CONFIRMED/CANCELLED default CONFIRMED |
| payment_group_id | UUID | FIFO group |
| notes | TEXT | |
| paid_at | TIMESTAMPTZ | |

Outstanding = `bills.total_amount − SUM(confirmed payments)`.

### 4.11 `outbox_events` (V19)

JSON payload as TEXT; status PENDING/PROCESSING/PROCESSED/FAILED; idempotency unique index. Laravel: `jobs`/`failed_jobs` or keep an outbox table.

### 4.12 `shop_settings` (V22 + V25 + V27)

Singleton per schema → UNIQUE(`shop_id`) in MySQL.

Business: `business_name`, `logo_attachment_id`, `business_type`, address/city/state/pincode, mobile, email, gstin, pan, website.  
Regional: currency, currency_symbol, decimal_precision, timezone, language, date_format.  
Bill: paper_size, bill_template_id, show_logo/gst/qr/customer_address/amount_in_words/signature.  
Print extras: upi_id, signature_attachment_id, watermark_text.  
Bank: bank_name, bank_account_no, bank_ifsc, bank_branch.  
Footer: terms_and_conditions, warranty_text, footer_message.  
Printer: thermal_width, margin, font_size.

### 4.13 `attachments` (V23)

`purpose`, `file_name`, `content_type`, `size_bytes`, `storage_key`. Files on disk — copy `STORAGE_LOCAL_PATH` tree keyed by tenant today; MySQL path must include `shop_id` to avoid collisions (`uploads/{shopId}/...`).

### 4.14 `number_series` (V24)

`series_type`, `prefix`, `financial_year_format`, `starting_number`, `current_number`, `is_active`.  
**Must preserve `current_number`** or invoice numbers will collide/reuse.

---

## 5. Standard audit columns (almost every table)

From `AuditableEntity` / `BaseEntity`:

`id`, `deleted`, `deleted_at`, `version` (optimistic lock), `created_at`, `updated_at`, `created_by`, `updated_by`.

Hibernate `@SQLDelete` / `@SQLRestriction(deleted=false)`. Laravel: global soft deletes + `shop_id` scope.

---

## 6. Relationships (logical)

```
public.shops 1 ──< tenant schema (today) / shop_id rows (MySQL)
public.permissions 1 ──< role_permissions.permission_id
public.bill_templates 1 ──< shop_settings.bill_template_id

roles 1──< users
roles 1──< role_permissions
users 1──< refresh_tokens
customers 1──< bills
bills 1──< bill_items
bills 1──< payments
attachments <── shop_settings.logo_attachment_id / signature_attachment_id
products  ⋯  bill_items.product_id   (no FK)
```

---

## 7. Sequences and counters to preserve

| Source | Preserve |
|---|---|
| Each table `id` BIGSERIAL | Max(id) per shop → MySQL AUTO_INCREMENT after load **per table globally** (IDs will no longer collide across shops once `shop_id` exists — **decision:** keep original per-schema ids (colliding) + shop_id, or remap to globally unique ids). **Recommendation:** remap to new globally unique BIGINTs **and** store `legacy_id` for support, **or** keep old ids and always key `(shop_id, id)`. Angular only uses ids inside one session, so `(shop_id, id)` matching old ids is the least surprising. Prefer **keep original ids + shop_id**. Then MySQL PK should be composite `(shop_id, id)` **or** a new surrogate `id` with unique `(shop_id, legacy_id)`. Laravel default `id` PK is simpler with **new global ids** and session-scoped queries — but it **breaks** stored PDF URLs and any external id references. **Recommend: new global BIGINT PK named `id`, unique `(shop_id, source_id)`, expose `id` as today. Angular never sees source_id.** Flag as Phase 1 decision — **approval required.** |
| `number_series.current_number` | Copy verbatim |
| `users.token_version` | Copy; invalidate all refresh tokens on cutover anyway |

---

## 8. Redis (not SQL, but part of runtime state)

Keys (approximate): schema name by tenant id, shop status, permission sets, token versions, login rate-limit counters.

**Do not migrate Redis.** After cutover, caches rebuild. All refresh tokens should be revoked so users log in once on Laravel.

---

## 9. File storage mapping

| Today | MySQL/Laravel |
|---|---|
| `./uploads` + `storage_key` | `storage/app/shop/{shop_id}/...` |
| Content types PNG/JPEG | unchanged |

Copy files in Phase 5 with a manifest `(shop_id, attachment_id, old_key, new_key)`.
