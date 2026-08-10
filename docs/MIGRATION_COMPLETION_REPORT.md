# ShopLedger Migration — Completion Report

**Status: Phase 1 (repository cleanup / rename / archival) complete and verified.**
Phase 2 (Shop Settings, Dashboard, Daily/Customer Report, Thermal Printing, Bill Templates) has **not** been started, per the approved scope boundary.

---

## Summary

The repository has been transformed from the original Multi-Tenant Billing System (MTBS — a SaaS platform that billed *tenants* for subscriptions) into ShopLedger (a retail shop billing app where each shop bills *its own customers*). The platform-billing/subscription engine was archived rather than deleted, the retail-billing domain was renamed into ShopLedger's vocabulary, and the full test suite plus a live, manually-exercised instance of the app confirm the result works end-to-end.

- **7 commits** since the pre-migration checkpoint (`e6c5f3f`)
- **100 files** archived into `legacy.saasbilling` (compiles independently, zero references from active code — verified by grep sweep)
- **200 files** remain in the active `com.mtbs` application
- **10 test classes**, **56 tests**, all passing against a real local PostgreSQL 17 + Redis instance
- **3 new Flyway migrations** (V9 public, V20–V21 tenant) — no existing migration was edited or deleted
- Full manual smoke test (signup → login → customer → product → bill → payment → reports) passed against a locally running instance

---

## Files modified (renamed in place, logic changed)

| Area | Files | What changed |
|---|---|---|
| Domain rename | `Tenant→Shop`, `BusinessInvoice→Bill`, `BusinessInvoiceItem→BillItem`, `BusinessPayment→Payment` (entities, controllers, services, repositories, mappers, DTOs — ~30 files) | Class/field names, package-internal references, `@Table`/`@SQLDelete` SQL strings updated to match renamed tables |
| `SignupService` | 1 | Removed `TenantOnboarding`/`PlanService` dependency; shop activates immediately (no onboarding wizard) |
| `AdminTenantService`/`Controller`, DTOs | 4 | Removed plan-change endpoint and plan fields |
| `QuartzConfig` | 1 | Gutted — 6 `JobDetail`/`Trigger` beans referencing now-legacy Job classes removed; left as a dormant placeholder |
| `WebMvcConfig` | 1 | Removed `PlanLimitInterceptor` registration |
| `NotificationEvent`, `EmailTemplateConfig`, `AuditEntityType`, `Status` | 4 | Trimmed constants/mappings only the archived module used |
| `GlobalExceptionHandler` | 1 | Removed the now-dead `RazorpayException` handler |
| `PlatformAdmin` | 1 | Added `@Builder.Default` (fixed a Lombok warning) |
| `Dockerfile` | 1 | JDK 21 → JDK 17 to match `pom.xml` |

## Files renamed

9 files under `tenant/` (`Tenant*→Shop*`), 13 files under `business/invoice/*` (`BusinessInvoice*→Bill*`), 6 under `business/payment/*` (`BusinessPayment*→Payment*`), 3 under `business/report/*` (dropped the now-vestigial "Business" prefix).

## Files archived (moved to `legacy.saasbilling`, unmodified logic)

~100 files: the entire `billing/*` platform-billing engine (subscriptions, plan pricing, proration, usage metering, Razorpay subscription payments/webhooks, 6 scheduled jobs), the `tenant.Plan*` family (entity/controller/service/repository/mapper/interceptor/11 DTOs), `UsageTrackingAspect`, `FeatureGate`/`TrackUsage` annotations, and the billing-only enums/events/exceptions. Sits outside `com.mtbs` so Spring's component scan never sees it — verified zero active beans, zero active imports.

## Files removed outright

Onboarding wizard (`TenantOnboarding` entity, controller, service, repository, 6 DTOs, 3 enums), `AdminMetrics*` (SaaS admin metrics), 19 orphaned email templates, obsolete test files (`billing/*Test`, `BillingFlowIntegrationTest`, `WebhookIntegrationTest`).

## Database changes

New migrations only — no existing migration edited or deleted:

- `V9__drop_saas_billing_and_rename_tenants_to_shops.sql` (public) — drops `plans`/`plan_features`/`plan_limits`/`plan_pricing`/`tenant_onboarding`, drops `plan_id`/`onboarding_step` from tenants, renames `tenants`→`shops`
- `V20__drop_platform_billing_tables.sql` (tenant) — drops `subscriptions`/`usage_records`/`usage_summaries`/`invoices`/`invoice_line_items`/`payments` (the platform ones)
- `V21__rename_business_tables_to_bill_domain.sql` (tenant) — renames `business_invoices→bills`, `business_invoice_items→bill_items`, `business_payments→payments`; drops `razorpay_payment_link_id`/`razorpay_customer_id`

Verified via `flyway:validate`/`flyway:info` against a fresh database (9/9 public migrations applied cleanly) and via the full tenant-schema test suite (fresh schema provisioned and torn down per test, 56/56 passing).

---

## Bugs found and fixed during verification

None of these were caught by `mvn compile` — they're all syntactically correct Java pointing at stale SQL or stale test wiring. Found only by actually running the test suite against a live database and exercising the running app:

1. **`Shop`/`Bill`/`BillItem`/`Payment` entities still pointed at pre-rename table names** in `@Table`/`@SQLDelete` (`tenants`, `business_invoices`, `business_invoice_items`, `business_payments`) even though the Java classes were renamed and the tables themselves were renamed by V9/V21. Every query against these four entities was broken at runtime.
2. **Leftover `razorpayPaymentLinkId`** field/column references in `Bill`, `Payment`, their DTOs, and mappers — the column was dropped by V21 and the feature was explicitly cut from V1 scope, but the Java field survived.
3. **`SignupService.deriveProvisionalSlug`** — `Math.min` compared the substring bound against the *original* (uncleaned) email-prefix length instead of the *cleaned* one, so any signup email with a long local-part containing symbols (`+`, `.`, `_`) crashed with `StringIndexOutOfBoundsException`. Verified pre-existing (identical in the original pre-migration commit) — never caught before because there was no CI and this test suite had never successfully run end-to-end.
4. **`AuthServiceTest`** predated the migration and never matched the real `AuthService` API (wrong method arity, flat fields on a since-restructured nested `AuthResponse`, `tenantId` instead of `tenantSlug`) — rewritten against the real API, per your explicit approval.
5. **`RoleServiceTest`** never set up a tenant schema — roles/permissions live in tenant schemas, not `public`; every test would have failed against a real database. Pre-existing, fixed.
6. **`hibernate.default_schema: public`** in the test profile defeated `SchemaBasedMultiTenantConnectionProvider`'s per-connection `search_path` switching, forcing every tenant-schema query to explicitly (and wrongly) target `public`. Pre-existing, affected the entire test suite. Removed.
7. **Quartz test config** — the base config's JDBC job-store properties (`driverDelegateClass`, `useProperties`, etc.) leaked into the test profile's Map-typed properties regardless of `job-store-type`, breaking `RAMJobStore`. Fixed by excluding `QuartzAutoConfiguration` for the `test` profile (no active bean needs a live `Scheduler` in tests).
8. **`TenantFlywayMigrationServiceTest`** asserted `subscriptions`/`invoices` tables exist post-migration — they're dropped by V20. Updated assertions.
9. **`ArchitectureRulesTest`** looked up beans (`subscriptionService`, `invoiceService`) that no longer exist in the active context since they moved to `legacy.saasbilling`.
10. **Customer/Product `@SQLDelete`** was missing `AND version = ?` for these `@Version`-annotated (optimistic-locking) entities, causing `DataIntegrityViolationException` on delete. (Found by the harness/linter mid-session, already fixed by the time of final verification.)

All of the above are now fixed and covered by either the automated test suite or the manual smoke test.

---

## Verification results

| Check | Result |
|---|---|
| `mvn clean compile` | **Pass**, zero warnings in active code (3 pre-existing MapStruct warnings remain in the inert `legacy.saasbilling` mappers — verified byte-identical logic to the original pre-migration commit) |
| `mvn test` | **Pass** — 56/56, 0 failures, 0 errors |
| `flyway:validate` / `flyway:info` | **Pass** — 9/9 public migrations validated and applied cleanly on a fresh database |
| No active → `legacy.saasbilling` references | **Pass** — confirmed via `grep -rl "import legacy\." src/main/java/com/mtbs src/test/java/com/mtbs` → zero hits |
| Application starts successfully | **Pass** — dev profile, real Postgres + Redis, clean startup, no errors |
| Signup flow | **Pass** — shop `ACTIVE` immediately, no onboarding step, cookies set |
| Customer flow | **Pass** — create verified |
| Product flow | **Pass** — create verified |
| Bill creation | **Pass** — line items, tax calc, product-price snapshotting all correct |
| Bill finalize | **Pass** — DRAFT → OPEN |
| Payment recording | **Pass** — auto-transitions bill OPEN → PAID, outstanding correctly recalculates to 0 |
| Reports (revenue/outstanding/monthly) | **Pass** — all three verified with correct aggregated figures against the smoke-test data |

---

## Remaining technical debt

- **REST paths still say `business-invoices`/`business-payments`** (e.g. `POST /api/v1/business-invoices`) even though the Java classes and DB tables are now `Bill`/`Payment`. URL renaming was never in scope for this migration — flagging it as a deliberate decision point for you, since it's a breaking API-contract change if/when made.
- **3 pre-existing MapStruct "unmapped property" warnings** in the archived `legacy.saasbilling` mappers (`PlanMapper`, `SubscriptionMapper`) — harmless (dead code, never invoked), left as-is since touching legacy logic was out of scope.
- **No CI/CD pipeline exists.** This is almost certainly *why* so many of the pre-existing bugs above (`hibernate.default_schema`, missing `RoleServiceTest` schema setup, the broken `AuthServiceTest`, the Quartz test config, the `deriveProvisionalSlug` crash) survived undetected — the test suite had, as far as I can tell, never actually been run successfully end-to-end before this session. Now that it passes cleanly, standing up a basic CI workflow (even just `mvn test` on push) would be high-value to keep it that way.
- **No `ProductServiceTest`** exists despite `Product` being a fully-formed module (pre-existing gap, noted in the original analysis).
- **`CreateBillRequest.items`** has a docstring saying items are "recommended but not required," but `@NotEmpty` actually requires at least one — pre-existing doc/behavior mismatch, not fixed (out of scope, no functional bug).
- **Local dev environment has no documented setup path with sane defaults** — `application.yaml` requires ~15 env vars with no fallbacks (`JWT_REFRESH_ISSUER`, `CORS_ALLOWED_ORIGINS`, etc.), and there's no local Postgres/Redis install guidance beyond `docker-compose.yml` (which needs Docker). Worth a short "local dev setup" doc.

## Risks

- The archived `legacy.saasbilling` module (~100 files, including `razorpay-java` as a live dependency) still compiles into every build. It's fully inert (outside the component-scan root, its own Flyway migrations were never re-added), but it does add to build time and binary size. This was an explicit, approved trade-off for future SaaS-subscription reactivation.
- This verification environment had no Docker and no Redis/Postgres pre-installed — I created a local Postgres database/user (`mtbs`/`mtbs_test`) and downloaded a portable Windows Redis binary to actually run the suite and the app. Your real dev/CI environment may differ; the fixes themselves (schema resolution, Quartz test config, entity table names) are environment-independent, but you should re-confirm `mvn test` passes in whatever environment you actually develop in.

## Recommended next phase

Phase 1 is stable — clean compile, full green test suite, verified live app. Suggested order for what comes next:

1. **Stand up basic CI** (`mvn test` on every push) — cheapest possible insurance against regressing back to the state this session found the suite in.
2. **Decide on the `business-invoices`/`business-payments` URL rename** — a deliberate call, not a migration afterthought.
3. **Phase 2 build-out**, per the original plan's inventory: Shop Settings (address/logo/tax id/currency/thermal-printer width), Dashboard, Daily Report, Customer Report, Thermal Printing, Bill Templates — each deserves its own short design pass before implementation, as originally scoped.

Per your explicit instruction, no Phase 2 work has been started. Awaiting your go-ahead.
