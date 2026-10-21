# PHASE 0 — Repository Audit (ShopLedger → Laravel + MySQL)

**Date:** 2026-10-14  
**Scope:** Read-only inspection of the existing repository. No application code, schema, or data was changed.  
**Repository root:** `Multi_Tenant_Billing_System-main/` (nested inside the workspace folder of the same name)  
**Product name in UI/code:** ShopLedger (backend still branded MTBS in README / Maven artifact)

This audit is based on source, Flyway SQL, Angular services, tests, and config. Older files under `docs/` (especially `SHOPLEDGER_ANALYSIS.md`) are **stale** and must not be treated as current: they claim there is no frontend, incomplete shop settings, and no thermal printing. Those claims are false as of this inspection.

---

## 1. Repository structure

```
Multi_Tenant_Billing_System-main/
├── pom.xml                          Spring Boot 3.4.3, Java 17 bytecode
├── Dockerfile                       Bundles Angular static into the JAR
├── docker-compose.yml               app + postgres:16 + redis:7
├── render.yaml                      Free Render blueprint (Postgres + Redis)
├── .env.example                     Required env vars (do not commit .env)
├── src/main/java/com/mtbs/          Active ShopLedger backend
├── src/main/java/legacy/saasbilling/ Archived SaaS-subscription module (still compiled)
├── src/main/resources/db/migration/
│   ├── public/                      V1–V12 shared schema
│   └── tenant/                      V1–V28 replayed per shop schema
├── src/test/java/                   17 test classes (local Postgres + Redis)
├── frontend/                        Angular 20.3 SPA (Material + Tailwind 4)
└── docs/                            Mixed: some current, much aspirational/legacy
```

There is **no Capacitor project**, no Android wrapper, and no Laravel directory. The Java backend must remain in place for the rest of this migration.

---

## 2. Frontend (Angular 20)

| Item | Finding |
|---|---|
| Location | `frontend/` |
| Framework | Angular **20.3**, TypeScript 5.9, Angular Material 20, Tailwind 4 |
| Routing | Standalone routes in `frontend/src/app/app.routes.ts` |
| API client | `ApiClient` + envelope unwrap (`success` / `data` / `errorCode`) |
| Auth | HttpOnly cookies (`withCredentials: true`). No access token in JS. |
| API base URL | Dev: `http://localhost:8080/api/v1`. Prod: relative `/api/v1`. |

### Screens that exist and talk to the API

| Route | Feature | Primary services |
|---|---|---|
| `/login`, `/signup` | Tenant auth | `AuthService` |
| `/admin/login`, `/admin/tenants` | Platform admin | `AdminAuthService`, `AdminTenantService` |
| `/dashboard` | Revenue / outstanding / recent bills | `BillService`, `ReportService` |
| `/customers`, `/customers/:id` | CRUD + walk-in + outstanding | `CustomerService`, `PaymentService` |
| `/products` | Catalog + deactivate/reactivate | `ProductService` |
| `/bills`, `/bills/new` | List + POS-style create | `BillService` |
| `/payments` | Invoice and FIFO customer payments | `PaymentService` |
| `/reports` | Revenue, outstanding, monthly | `ReportService` |
| `/shop-settings` | Business info, print, bank, logo/signature | `ShopSettingsService` |

### Frontend not implemented (API exists)

- Forgot / reset password UI (`POST /auth/forgot-password`, `/auth/reset-password`)
- Tenant user management UI (`/users`)
- Role / permission management UI (`/roles`, `/permissions`)
- Shop self-service deactivate/reactivate (`/tenant`)
- Two-step email→tenant picker (`POST /auth/tenants`) — login requires `tenantSlug` as a form field
- Capacitor / Android packaging

### POS billing UI flow (important for API compatibility)

Create bill is **not** a single “save draft” action. `bill-create.ts` chains:

1. `POST /business-invoices` (DRAFT, server calculates totals)
2. `POST /business-invoices/{id}/finalize` (OPEN)
3. `POST /business-payments/{id}` with **client-computed** `grandTotal` and selected method (Cash / UPI / Card / Pay Later)

NETBANKING and BANK_TRANSFER are in the backend enum and Angular type, but the create-bill chips only expose CASH, UPI, CARD, CREDIT.

Printing is PDF download (`GET .../download` as `Blob`) opened in a new tab — not ESC/POS. Paper size is a shop setting consumed by **server-side iText** renderers.

---

## 3. Backend (Spring Boot)

| Item | Finding |
|---|---|
| Boot | 3.4.3 |
| Java | Maven `java.version=17`; Dockerfile uses Temurin 21 |
| Persistence | Hibernate SCHEMA multi-tenancy, `ddl-auto: none` |
| Migrations | Flyway programmatic (YAML `flyway.enabled: false`; custom runner) |
| Auth | JWT HS256 (`jjwt` 0.12.6), cookies + optional `Authorization: Bearer` |
| Passwords | BCrypt |
| Cache | Redis (schema name, shop status, permissions, token version, login rate limit) |
| Jobs | Quartz JDBC (clustered in prod YAML); daily subscription expiry job |
| Mail | SMTP (Brevo-oriented env vars); transactional outbox |
| Files | Local disk `STORAGE_LOCAL_PATH` (default `./uploads`), max 2MB, PNG/JPEG |
| PDF | iText 8 (`BillPdfService` + template renderers) |
| API envelope | `ApiResponse<T>` `{ success, message, data, errorCode, fieldErrors, timestamp }` |
| Version prefix | `/api/v1` (`api.version=v1`) |

### Module map (active `com.mtbs`)

| Package | Responsibility |
|---|---|
| `app` | Boot, filters, global exception handler, health |
| `auth` | JWT, RBAC, users, roles, permissions, signup/login, admin auth |
| `tenant` | Shop registry, Flyway schema provisioning, settings, attachments, number series, templates |
| `business` | Customers, products, bills, payments, reports, PDF |
| `admin` | Cross-tenant shop admin, audit log API, subscription expiry |
| `notification` | Email templates / async send |
| `shared` | Tenancy plumbing, DTOs, enums, outbox, storage port |

`legacy.saasbilling` remains on the compile path (plans, Razorpay webhooks, platform invoices/subscriptions). Public/tenant **tables** for that domain were dropped (public V9, tenant V20). Treat it as dead surface area unless Angular starts calling it (it does not).

Controllers, routes, authz, and DTO notes: see `API_INVENTORY.md`.

---

## 4. Tenant isolation (critical)

**Current model: PostgreSQL schema-per-tenant, not shared tables and not database-per-tenant.**

1. `public.shops` holds one row per shop: `id`, `schema_name` (unique), `slug`, `status`, `owner_email`, plan/expiry columns.
2. Signup creates schema `schema_{emailPrefix}_{epochMillis}` and runs `db/migration/tenant/*`.
3. `JwtAuthenticationFilter` sets `TenantContext` **only from validated JWT claims** (`tenantId`), never from body/query.
4. Hibernate `SchemaBasedMultiTenantConnectionProvider` runs `SET search_path TO "<schema>", public`.
5. Tenant business tables **do not** have `tenant_id` / `shop_id`. Isolation is the search path.
6. Primary keys collide across shops (each schema’s first customer is `id=1`). Cross-tenant tests must compare **content**, not IDs.

Shop statuses (`Status`): `ACTIVE`, `INACTIVE`, `SUSPENDED`, `REGISTERED`, `PENDING_APPROVAL`.

Signup persists `PENDING_APPROVAL`. Owners can log in and **read**; non-GET writes are blocked with `403 TENANT_PENDING_APPROVAL` until `POST /admin/tenants/{id}/approve`. `SUSPENDED` / `INACTIVE` are blocked at login/refresh.

**This is not something Laravel/MySQL can copy as PostgreSQL schemas.** MySQL “schema” = database. Shared PHP hosting usually cannot create one database per shop. Phase 1 must choose an explicit MySQL strategy; see recommendation below and `MIGRATION_RISKS.md`.

---

## 5. Authentication and authorization

### Tenant users

- Login: `POST /api/v1/auth/login` `{ email, password, tenantSlug }`
- Cookies: `access_token` (path `/`), `refresh_token` (path `/api/v1/auth/refresh`), `SameSite=Lax`
- JWT claims include user id, tenant id, role id, token version
- Refresh rotates tokens; logout revokes refresh token in the **tenant** `refresh_tokens` table
- Token version in Redis enables instant revoke without a denylist
- Login rate limit: 5 failures / 15 minutes per IP (Redis)
- Permissions stored in `public.permissions`; assignments in tenant `role_permissions`
- `@PreAuthorize("hasAuthority('PERMISSION_*')")` — filter prefixes `PERMISSION_` onto cached names

Seeded roles per tenant: `OWNER`, `ADMIN`, `EMPLOYEE`.

Seeded permissions: `TENANT_VIEW`, `TENANT_MANAGE`, `USER_VIEW`, `USER_MANAGE`, `USER_DELETE`, `ROLE_VIEW`, `ROLE_MANAGE`, `BILLING_MANAGE`, `CUSTOMER_MANAGE`, `PRODUCT_MANAGE`.

### Platform admin (separate cookie/JWT)

- Table: `public.platform_admins`
- Endpoints: `/api/v1/admin/auth/*`
- Authority: `SUPER_ADMIN` (no tenant id)
- **Security debt:** `V6__seed_super_admin.sql` inserts `admin@platform.com` with a known BCrypt hash of a default password. `.env.example` also has `ADMIN_PASSWORD`. Do not copy this seed into Laravel as-is.

### Public security config notes

`SecurityConfig` permits login/signup/refresh/forgot/reset, admin login/refresh, health, swagger, **and `/actuator/**`**. Actuator exposure is a production risk.

`POST /api/v1/auth/tenants` is **not** in the public matcher list, so it requires authentication. Angular does not use it.

CSRF is disabled (stateless JWT + SameSite cookies). CORS is env-driven with credentials.

---

## 6. Billing, discounts, payments, invoice numbers

### Lifecycle

`DRAFT` → `OPEN` (finalize) → `PAID` (confirmed payments cover total) or `VOID`.  
Enum also has `UNCOLLECTIBLE` (frontend types it; no dedicated API found in controllers).

### Server calculation (must be preserved)

Using `BigDecimal`, `HALF_UP`, scale 2:

```
lineBase   = ROUND(unitPrice × qty, 2)
itemTax    = ROUND(lineBase × taxPct / 100, 2)
itemTotal  = ROUND(lineBase + itemTax, 2)
subtotal   = Σ lineBase
taxAmount  = Σ itemTax
totalAmount = ROUND(subtotal + taxAmount − discountAmount, 2)
```

- Discount is **bill-level only**
- Negative grand total is rejected
- Catalog `productId` **snapshots** name/price/tax; request price is ignored
- Totals are stored; not recomputed on read
- `@Transactional` on create / item edit / finalize / void / payments
- Pessimistic row locks on bills during payment allocation

### Invoice numbers

`NumberSeriesService.nextNumber(INVOICE)` with `PESSIMISTIC_WRITE` on the active series row.

Default seed: prefix `INV`, `financial_year_format=NONE`, `starting_number=1`, `current_number=0`.  
Format: `{prefix}-{FY segment}{seq padded to 4}` e.g. `INV-0001`.

Uniqueness: `UNIQUE(invoice_number)` **per tenant schema**. After a shared-table MySQL move, uniqueness must become `(shop_id, invoice_number)`.

### Payments

Methods: `CASH`, `CARD`, `UPI`, `NETBANKING`, `BANK_TRANSFER`, `CREDIT`.

- Non-CREDIT → `CONFIRMED`; if Σ confirmed ≥ total → mark PAID
- CREDIT → `PENDING`; never marks PAID until `PATCH .../confirm`
- Partial payments allowed; amount cannot exceed outstanding (confirmed only)
- Customer FIFO payment fans out oldest OPEN bills; sets `payment_group_id` (UUID)
- Void blocked if PAID or if a PENDING credit exists

### Angular payment amount risk

Create-bill records payment using **UI `grandTotal`**, not `bill.totalAmount` from the create response. If catalog snapshot or rounding differs, the payment call can 400. Laravel must keep the same server rules; optionally (later, with approval) return totals and have Angular pay `data.totalAmount`.

---

## 7. Customers and products

**Customers** (tenant schema): name, email, phone, address, gstin, `is_walkin`. Soft delete. No unique email. Walk-in row seeded (`is_walkin=true`, name `Walk-in Customer`) and protected from delete/rename. `GET /customers/walkin`.

**Products:** name, description, price `NUMERIC(12,2)`, tax `NUMERIC(5,2)`, hsn_sac_code, unit, `is_active`. DELETE deactivates; `POST /{id}/reactivate`. No FK from bill lines to products (historical snapshot).

---

## 8. Shop settings and printing

`shop_settings` is a **singleton row** per tenant schema.

Business type enum **today**: `RETAIL`, `WHOLESALE`, `SERVICE`, `RESTAURANT`, `PHARMACY`, `OTHER`.  
Requested Phase 4 types (grocery, mobile/electronics, salon, repair, hotel/café) are **not** the current enum. Do not silently remap; Phase 4 should extend the catalog.

Paper sizes: `A4`, `THERMAL_58MM`, `THERMAL_80MM`.

Public `bill_templates`: `CASH_MEMO_V1`, `MARATHI_CASH_MEMO_V1`. Renderer key = `{code}:{paperSize}`.

Attachments: logo/signature on disk; streamed at `GET /attachments/{id}/file` (**authenticated, but not `TENANT_MANAGE`** — any logged-in tenant user in that schema). Upload restricted to PNG/JPEG, ≤2MB, no WebP.

UPI QR is generated server-side from `upi_id` when `show_qr_code` is true.

**Angular printing must stay as-is:** it only downloads a PDF. Thermal layout is PDF page size, not a native printer protocol.

---

## 9. Reports and admin

Reports (tenant, `BILLING_MANAGE`):

- `GET /reports/revenue?from&to` — confirmed payments by `paid_at`, method breakdown
- `GET /reports/outstanding` — OPEN bills
- `GET /reports/monthly?year` — 12-month invoice/collected/outstanding

Admin: list/detail/status/approve/reactivate/users/soft-delete shops; audit log query APIs; subscription expiry job (alert 5 days before, suspend on expiry).

Audit logs live in **public** `audit_logs` (JSONB). Domain events also go through **per-tenant** `outbox_events`.

---

## 10. Tests, deploy, environment

### Tests (not executed in this phase)

| Class | Kind |
|---|---|
| `AuthServiceTest` | Unit |
| `RoleServiceTest` | Unit |
| `CustomerServiceTest` | Unit |
| `BillServiceTest` | Unit |
| `PaymentServiceTest` | Unit |
| `ShopSettingsServiceTest` | Unit |
| `BillTemplateServiceTest` | Unit |
| `NumberSeriesServiceTest` | Unit |
| `AttachmentServiceTest` | Unit |
| `BillPdfServiceTest` | Unit |
| `TenantFlywayMigrationServiceTest` | Unit |
| `ArchitectureRulesTest` | ArchUnit-style |
| `MultiTenantBillingSystemApplicationTests` | Context |
| `MultiTenancyIntegrationTest` | Weak (manipulates `TenantContext`; isolation assertion historically weak) |
| `CrossTenantSecurityIntegrationTest` | Strong HTTP+cookie two-tenant isolation |

`application-test.yaml` requires local DB `mtbs_test` and Redis. Testcontainers is in the POM but unused.

Frontend: `frontend/src/app/app.spec.ts` only (Karma). No e2e suite.

### Deploy

- Docker Compose: Postgres 16 + Redis 7 + app
- Render blueprint: one web service serving JAR + Angular static
- Env: `DATABASE_*`, `REDIS_*`, `JWT_*`, `RAZORPAY_*`, `MAIL_*`, `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `ADMIN_PASSWORD`
- Prod Flyway is not enabled in YAML; public migrations run via a custom config bean in some profiles — verify before any cutover

### Incomplete / leftover

- Legacy SaaS billing Java still compiled
- Quartz tables still created in every tenant schema (`V11`)
- README still describes Razorpay subscription SaaS
- No GitHub Actions CI
- Hardcoded default platform admin in SQL
- `/actuator/**` public
- Attachment file GET weaker than upload
- `POST /auth/tenants` not public
- Business-type catalog does not match Phase 4 list
- No offline billing, no Capacitor

---

## 11. Migration recommendation (for Phase 1 approval)

**Do not implement yet.** Recommendation only:

1. **Keep the Spring Boot app and PostgreSQL source untouched.** Add a sibling Laravel API project (e.g. `backend-laravel/`).
2. **Do not emulate PostgreSQL schema-per-tenant on typical PHP/MySQL hosting** (that becomes one MySQL database per shop, usually blocked).
3. **Recommended MySQL model (subject to Phase 1 approval):** one shared database, shared tables, **`shop_id` on every tenant-owned table**, Laravel global scopes + policies, tenant taken **only from the authenticated session**, never from the client.
4. This **is** an isolation-model change. It is safer for shared hosting and query tooling, but **weaker at the database engine layer** than schema-per-tenant. Compensation: exhaustive authorization tests (port `CrossTenantSecurityIntegrationTest`), no mass-assignment of `shop_id`, and deny-by-default policies.
5. Preserve `/api/v1` paths, `ApiResponse` envelope, cookie names, and billing math so Angular 20 can switch with env URL only.
6. Auth: Laravel Sanctum (SPA cookies for the website; token ability for a future Capacitor app). Redis remains useful for rate limits/cache; if hosting has no Redis, document a fallback (cache driver / database) with the same login lockout semantics.
7. Data migration (Phase 5): read-only Postgres snapshot → empty MySQL target; preserve PKs **plus** `shop_id`; rematerialize sequences/`number_series.current_number`; JSONB → JSON; TIMESTAMPTZ → UTC `DATETIME(6)` or `TIMESTAMP`.

Companion documents:

- `docs/migration/API_INVENTORY.md`
- `docs/migration/DATABASE_MAPPING.md`
- `docs/migration/MIGRATION_RISKS.md`

---

## Phase 0 close-out

| # | Item | Value |
|---|---|---|
| 1 | Files created | `docs/migration/PHASE_0_AUDIT.md`, `API_INVENTORY.md`, `DATABASE_MAPPING.md`, `MIGRATION_RISKS.md` |
| 2 | Functionality implemented | None (documentation only) |
| 3 | Commands/tests executed | Repository file inspection only. **No** `mvn test`, **no** `ng test`, **no** SQL against any database |
| 4 | Test results | Not run |
| 5 | Security concerns | See `MIGRATION_RISKS.md` §security |
| 6 | Known issues | Stale `docs/SHOPLEDGER_ANALYSIS.md`; leftover `legacy.saasbilling`; default admin seed |
| 7 | Manual verification | Open this folder and spot-check controllers under `src/main/java/**/controller` vs `API_INVENTORY.md` |
| 8 | Remaining tasks | Phase 1 design after approval |
| 9 | Approval needed? | **Yes. Stop here.** |
