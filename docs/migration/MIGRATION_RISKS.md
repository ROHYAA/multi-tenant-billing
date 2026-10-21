# Migration risks — Spring Boot / PostgreSQL → Laravel / MySQL

Phase 0 identification only. No production cutover, no destructive SQL, no traffic switch.

---

## 1. Isolation model change (highest)

**Today:** PostgreSQL schema-per-tenant. A missing `WHERE tenant_id` cannot leak another shop’s rows; Hibernate search_path makes “wrong id” resolve to the **caller’s** same-numbered row.

**MySQL on typical PHP hosting:** cannot cheaply create one database per shop. Shared tables **require** `shop_id` on every query.

**Risk:** a single forgotten scope (raw SQL, report join, admin export, PDF loader, attachment path) becomes a **true cross-tenant leak**, which the old architecture largely could not do.

**Mitigations (Phase 1–8):**

- Tenant id only from authenticated session
- Laravel `BelongsToShop` global scope + deny-by-default policies
- Forbid request `shop_id` mass assignment
- Port `CrossTenantSecurityIntegrationTest` (content markers, not status codes)
- Code review of every `DB::` / query builder call
- Optional PostgreSQL-style defense in depth: if hosting allows, one database per shop — usually it does not

**Decision required in Phase 1:** shared tables vs database-per-tenant. Do not “silently” pick shared tables in implementation without approval.

---

## 2. Primary key collisions

Each shop’s `customers.id=1` is valid. After flatten:

- Keeping raw ids without `shop_id` in the PK **merges tenants**.
- Tests that assume global unique ids will misfire (the Java audit already documents this).

**Mitigation:** unique `(shop_id, id)` or new global ids + `source_id`. Record the choice in Phase 1.

---

## 3. Financial integrity

Rules that must not change silently:

- `BigDecimal` HALF_UP scale 2
- Tax on line base, discount bill-level, `total = subtotal + tax − discount`
- Catalog snapshot vs free-text lines
- CREDIT is PENDING; only CONFIRMED counts toward paid/revenue
- FIFO allocation + pessimistic locks
- Invoice numbers from locked `number_series`, not `COUNT(*)`

**Risks:**

- PHP `float` in JSON/request casting
- Laravel `$casts` to `float`
- MySQL `DOUBLE`
- Angular sending UI `grandTotal` as payment amount (pre-existing). If Laravel rounding differs by ₹0.01, POS save fails.

**Mitigation:** `DECIMAL` columns, `bcmul`/`Brick\Money` or string decimals, tests copied from `BillServiceTest` / `PaymentServiceTest` with identical fixtures.

---

## 4. Invoice numbers and sequences

`current_number` and unique `invoice_number` per shop must survive. Concurrent bill creation needs a row lock (`SELECT ... FOR UPDATE`) equivalent.

After flatten, `UNIQUE(invoice_number)` globally would **fail** (two shops both `INV-0001`). Uniqueness must be per shop.

---

## 5. Authentication and Android

Website: HttpOnly cookies, `SameSite=Lax`, refresh path `/api/v1/auth/refresh`, silent refresh interceptor.

**Risks:**

- Sanctum CSRF cookie vs current CSRF-disabled API
- `SameSite` / `Secure` on HTTPS vs Android WebView
- Refresh cookie path too narrow for a new URL layout
- Capacitor cannot use `withCredentials` the same way unless the API is HTTPS and CORS/cookie domain are correct
- Bearer header already supported by Java filter — good for Android if cookies fail

**Mitigation:** keep cookie behavior for the SPA; add Sanctum tokens for Capacitor in Phase 7; never put JWT in `localStorage` for the website without an explicit security exception.

Pending-approval write block lives in `JwtAuthenticationFilter`, not in each controller. Easy to omit in Laravel middleware.

---

## 6. Redis and rate limits

Java depends on Redis for:

- Schema name / shop status cache
- Permission cache
- Token version (logout-all)
- Login lockout

PHP shared hosting may lack Redis.

**Risk:** missing Redis → either boot failure or **no lockout / no revoke**.

**Mitigation:** Laravel cache driver configurable (redis/database/file) with documented behavior; token version can live on `users.token_version` only (already in SQL).

---

## 7. Passwords and seeded admin

BCrypt hashes must verify in Laravel (`Hash::check`). Do not re-hash during migration.

`V6__seed_super_admin.sql` contains a well-known default admin. Migrating that row into MySQL **copies a public password**. Create a new admin via env on first Laravel boot instead; migrate only if the production password was rotated.

`.env` / `.env.example` must never be committed with real secrets.

---

## 8. Time zones and TIMESTAMPTZ

Source is `timestamptz` + Java `Instant`. Shop default TZ `Asia/Kolkata`. Reports use Instant query params from Angular `toISOString()`.

MySQL `TIMESTAMP` converts using session TZ. A wrong `time_zone` on import shifts `paid_at` and revenue reports.

**Mitigation:** store UTC `DATETIME(6)`, set app timezone, convert in API layer; reconciliation compares epoch millis.

---

## 9. JSONB audit logs

MySQL JSON is close but not identical (indexing, containment queries). GIN indexes will not transfer. Admin audit UI is unused in Angular today — lower product risk, still a data-loss risk if dropped.

---

## 10. Files and PDFs

Logos/signatures are not in Postgres. Missing file copy → blank logos, failed PDF QR/signature.

PDF is generated in Java iText with specific templates (A4 / 58mm / 80mm / Marathi). Laravel must reproduce **layout enough that shops accept reprints of new bills**. Historical PDFs if stored via `pdf_url` should be copied as blobs; many bills generate PDF on download only — reprints of **old** bills will use **new** renderer. Visual drift is expected unless pixels are frozen.

Thermal “printing” is PDF, not ESC/POS. Android WebView print-to-printer may differ. Do not claim hardware printer compatibility until Phase 7 tests a real device.

---

## 11. Jobs, outbox, mail, Quartz

Outbox + Quartz + subscription expiry + audit cleanup + mail. Laravel: scheduler + queues. Shared hosting often has **no long-running queue worker** and unreliable cron.

**Risk:** missed expiry suspend, missed welcome email, stuck outbox.

**Mitigation:** document cron `* * * * * php artisan schedule:run`; optional sync mail in Phase 2; keep Java jobs running until cutover.

---

## 12. Leftover / dual stack

- `legacy.saasbilling` still in the JAR — do not migrate those APIs
- Quartz tables in tenant schemas — skip
- README/docs stale — operators may follow wrong runbooks
- Two backends after Phase 2: Angular must point at **one** API via env. Split-brain writes (some users on Java, some on Laravel) will diverge financial data

**Cutover rule:** freeze writes or dual-write with a single leader. Phase 9; not now.

---

## 13. Hosting and PHP version

Phase 2 must **inspect the real PHP version** before locking Laravel. Laravel 11 typically wants PHP 8.2+. Many cPanel hosts are 8.1 or have missing `ext-intl`, `ext-gd`/`imagick` (PDF), `ext-redis`.

No PDF library chosen yet; iText is Java. Risk of layout rewrite.

Actuator and Swagger are publicly matched in `SecurityConfig` — do not replicate open actuator on Laravel.

---

## 14. Incomplete product vs requested industries

Generic billing exists. Missing as **completed** features (document as future, Phase 4):

- Grocery vs retail vs mobile-specific catalogs
- Hotel tables / KOT
- Salon appointments / commissions
- Repair tickets / IMEI tracking (partially possible as products/notes only)
- Business-type enum mismatch with requested list

Do not treat `BusinessType.RESTAURANT` as a restaurant module.

---

## 15. Testing gaps

- Integration tests need a local Postgres; they will not prove MySQL
- Frontend tests are almost absent
- `MultiTenancyIntegrationTest` is not a reliable isolation proof; `CrossTenantSecurityIntegrationTest` is
- This Phase 0 **did not run** the suite — do not treat historical green builds as current

---

## 16. Security checklist for later phases

| Item | Current | Laravel must |
|---|---|---|
| Tenant from JWT only | Yes | Yes |
| Cookie HttpOnly | Yes | Yes |
| BCrypt | Yes | Yes |
| Rate limit login | Redis | Equivalent |
| CORS allowlist | Env | Env |
| CSRF | Disabled | Sanctum SPA CSRF if cookie session |
| Mass assignment shop_id | N/A (schema) | Blocked |
| SQL injection | JPA params | Eloquent bindings; no string schema names |
| Default admin seed | Present | Do not copy |
| Attachment GET | Any tenant user | Re-evaluate; still tenant-scoped via search_path today |
| `/actuator/**` public | Yes | Do not copy |

---

## 17. Data migration operational risks

- Source must be snapshot (dump / replica) — never migrate the live primary in place
- Target MySQL empty and separate
- Dry-run + reconciliation: row counts, FK, Σ bill totals, Σ payments, outstanding per customer, invoice uniqueness per shop, orphan items
- Retry idempotency: upsert on `(shop_id, source_id)`
- Dual-write window after snapshot: Phase 9 cutover plan
- Do not delete PostgreSQL until explicit approval

---

## 18. Recommended sequence (after approval)

Phase 1 design (isolation decision, PK strategy, PHP version) → Phase 2 empty Laravel API + migrations → Phase 3 modules in the order given in the project brief → Phase 4 business-type field only → Phase 5 dry-run copy → Phase 6 Angular env → Phase 7 Capacitor prep → Phase 8 tests → Phase 9 runbook.

**Stop until Phase 0 documents are approved.**
