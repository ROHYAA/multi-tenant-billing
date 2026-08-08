# ShopLedger Migration Analysis

**Base repository:** Multi-Tenant Billing System (MTBS) — a Spring Boot SaaS subscription-billing platform
**Target product:** ShopLedger — a multi-client retail shop billing application
**Analysis date:** 2026-08-08
**Status:** Analysis only. No code was modified, renamed, or deleted as part of this document.

---

## How to read this document

This analysis is based on (a) direct inspection of `pom.xml`, `Dockerfile`, `docker-compose.yml`, `.env.example`, migration SQL, and a sample of entity/service/controller source files, and (b) the project's own `docs/` folder (13 sections, 40+ files).

**Important caveat surfaced during this analysis:** the existing `docs/` folder is significantly more aspirational than the code. Several documents describe infrastructure that does not exist in this repository — a React frontend, GitHub Actions CI, Spring Cloud Sleuth/Zipkin distributed tracing, Prometheus alerting, H2-based test databases, a working Testcontainers setup, and 82%+ verified test coverage. None of these were found in the actual codebase. Everywhere this document states a fact about the *running system*, it is grounded in source code, `pom.xml`, migration SQL, or config YAML — not in the prose docs. Where only the docs make a claim (and it wasn't independently verified in code), it is explicitly labeled "per project docs, unverified."

---

## STEP 1 — Full Codebase Analysis

### 1.1 Backend

**Architecture.** Modular monolith, one Spring Boot application, packages sliced vertically by business module under `com.mtbs`: `app` (bootstrap/config/filters/global exception handling), `auth` (JWT, RBAC, users), `tenant` (tenant onboarding, plans, schema provisioning), `billing` (platform/SaaS billing: subscriptions, invoices, payments, proration, usage metering), `business` (B2B/retail invoicing: customers, products, invoices, payments, reports), `notification` (async email via hexagonal ports/adapters), `admin` (cross-tenant platform-admin operations, audit log), `shared` (base entities, exceptions, multitenancy plumbing, common DTOs). Each business-facing module further splits into `controller / service / repository / entity / dto / mapper`. Approximate class counts: `auth` 67, `billing` 63, `tenant` 50, `shared` 50, `business` 42, `admin` 19, `notification` 15, `app` 11 — roughly 320 classes total, larger than the README's "250+" claim.

**Spring Boot / Java version.** Spring Boot `3.4.3` (parent POM), `java.version=17` in `pom.xml`. Hibernate `6.6.8.Final` and Spring Security `6.4.3` are pulled in via the Boot BOM (not explicitly pinned). **Version mismatch found:** the `Dockerfile` builds and runs on `eclipse-temurin:21-jdk-alpine` / `21-jre-alpine`, i.e. the container actually runs on JDK/JRE 21 even though the Maven build targets Java 17 bytecode — worth reconciling before ShopLedger's first deploy.

**Database.** PostgreSQL 14+ (compose uses `postgres:16-alpine`), Flyway-managed, **schema-per-tenant** multi-tenancy: every tenant gets a dedicated schema `s_{tenantId}`; the `public` schema holds platform-shared tables (tenants, plans, permissions, platform_admins, audit_logs, tenant_onboarding). Hibernate is wired for `SCHEMA`-mode multitenancy via a custom `SchemaBasedMultiTenantConnectionProvider` (issues `SET search_path`) and `CurrentTenantIdentifierResolverImpl` (reads a `TenantContext` ThreadLocal). See §1.3 for full schema detail.

**Security / Authentication.** Stateless JWT (HS256, `jjwt` 0.12.6), tokens delivered as HttpOnly/Secure cookies (`SameSite=Strict`/`Lax` depending on doc vs. code), with a separate super-admin token path (`isSuperAdmin` claim, no `tenantId`). `JwtAuthenticationFilter` (`auth/security/JwtAuthenticationFilter.java`) extracts the JWT, validates it, resolves `tenantId → schemaName` via a Redis-cached lookup, sets `TenantContext`, checks a Redis-cached **token version** (instant logout/revocation without a blocklist table), loads permissions from `PermissionCacheService` (Redis, 15 min TTL), and populates `SecurityContextHolder`. Passwords hashed with `BCryptPasswordEncoder`. `SecurityConfig` (`auth/config/SecurityConfig.java`) enables `@EnableMethodSecurity`, disables CSRF (justified by stateless JWT + SameSite cookies), configures CORS from `cors.allowed-origins`, and restricts `/admin/**` to `hasAuthority("SUPER_ADMIN")`.

**Authorization (RBAC).** `Role` and `RolePermission` live per-tenant-schema; `Permission` is a shared catalog in the `public` schema (`PERMISSION_TENANT_VIEW/MANAGE`, `PERMISSION_USER_VIEW/MANAGE/DELETE`, `PERMISSION_ROLE_VIEW/MANAGE`, `PERMISSION_BILLING_MANAGE`, `PERMISSION_CUSTOMER_MANAGE`, `PERMISSION_PRODUCT_MANAGE` — 10 seeded permissions). Enforced with plain `@PreAuthorize("hasAuthority('PERMISSION_X')")` across ~60 controller methods — no custom RBAC annotation. Three system roles seeded per tenant at signup: `OWNER`, `ADMIN`, `EMPLOYEE`; custom roles can be created and have permissions assigned/revoked via `RoleController`.

**Services / Repositories / Controllers / DTOs / Entities.** Standard Spring layering throughout: `@RestController → @Service → Spring Data JPA @Repository`, MapStruct mappers (`mapper` package in every module) convert between entities and request/response DTOs. 23 REST controllers total, all mounted under `/api/${api.version}` (i.e. `/api/v1`). Full controller/base-path list:

| Module | Controller | Base path |
|---|---|---|
| admin | AdminMetricsController | `/admin/metrics` |
| admin | AdminTenantController | `/admin/tenants` |
| admin | AdminUserController | `/admin/users` |
| admin | AuditLogController | `/admin/audit-logs` |
| auth | AuthController | `/auth` |
| auth | AdminAuthController | `/admin/auth` |
| auth | PermissionController | `/permissions` |
| auth | RoleController | `/roles` |
| auth | UserController | `/users` |
| tenant | TenantController | `/tenant` |
| tenant | OnboardingController | `/onboarding` |
| tenant | PlanController | `/plans` |
| billing | DashboardController | `/dashboard` |
| billing | InvoiceController | `/invoices` |
| billing | PaymentController | `/payments` |
| billing | SubscriptionController | `/subscriptions` |
| billing | UsageController | `/usage` |
| billing | RazorpayWebhookController | `/webhooks` |
| business | CustomerController | `/customers` |
| business | ProductController | `/products` |
| business | BusinessInvoiceController | `/business-invoices` |
| business | BusinessPaymentController | `/business-payments` |
| business | BusinessReportController | `/reports` |
| app | HealthCheckController | `/health` |

**Exception handling.** Centralized: `BaseException` (abstract) → domain subclasses `AuthException`, `PaymentException`, `ResourceException`, `SubscriptionException`, `TenantException`, `TokenException`, each paired with a ~35-entry `ErrorCode` enum (`AUTH_1xxx`, `TNT_2xxx`, `TKN_3xxx`, `RES_4xxx`, `PAY_5xxx`, `VAL_6xxx`, `SUB_7xxx`, `GEN_9001`) that maps 1:1 to an `HttpStatus`. `GlobalExceptionHandler` (`@RestControllerAdvice`) catches `BaseException`, validation exceptions, `AccessDeniedException`, malformed-body/type-mismatch exceptions, `RazorpayException`, and a generic catch-all — never leaks stack traces. Standard envelope `ApiResponse<T>` (`success`, `message`, `data`, `errorCode`, `fieldErrors`, `timestamp`). One inconsistency: the JWT filter's own 401 responses are hand-built JSON, bypassing `GlobalExceptionHandler`/`ApiResponse`.

**Logging.** Config purely via `logging.*` in YAML (no `logback-spring.xml`). Two servlet filters provide structured, correlated logs: `MdcLoggingFilter` (generates/propagates `X-Request-Id`/`X-Trace-Id`, logs method/URI/status/duration) and `MdcSecurityEnrichmentFilter` (adds `tenantId`/`userId`/`role` to MDC post-authentication). Console/file patterns embed `%X{tenantId}`/`%X{userId}` for per-tenant log correlation. `@Slf4j` (Lombok) used throughout; no custom logging wrapper.

**Validation.** Standard Jakarta Bean Validation (`@NotBlank`, `@Email`, `@Size`, etc.) on ~42 request DTOs — no custom `@Constraint` validators exist anywhere in the codebase. Validation failures are routed through `GlobalExceptionHandler` into field-level error maps.

**Reports.** `BusinessReportService` (`business/report/`) implements three reports today: a **Revenue report** (date-range sum + breakdown by payment method, via `GROUP BY`), an **Outstanding report** (open invoices split into current vs. overdue, invoice-level, not customer-grouped), and a **Monthly summary** (12-month loop of invoice count/total/collected/outstanding per month). All are tenant-scoped, gated by `PERMISSION_BILLING_MANAGE`, exposed under `/api/v1/reports/*`. No daily-granularity report and no customer-level report exist today.

**PDF generation.** Two independent iText 7 (`com.itextpdf.kernel`/`layout`, v8.0.5) generators exist: `business/invoice/service/BusinessInvoicePdfService.java` (B2B "TAX INVOICE" PDF for a `BusinessInvoice` — A4, colored header, bill-to block, itemized table, totals, notes) and `billing/service/InvoicePdfService.java` (simpler layout for the platform's own subscription invoices). **No thermal-printer / receipt support exists anywhere** — a repo-wide case-insensitive search for `thermal|escpos|receipt|printer` returned only unrelated matches (a code comment and Razorpay's API `receipt` field). This is a ground-up build for ShopLedger.

### 1.2 Frontend

**There is no frontend in this repository.** No `angular.json`, no `package.json`, no `frontend/` directory anywhere. The README's own "Project Status" section lists "Frontend dashboard (React/Vue)" under **Planned**, not built. Several `docs/` files (e.g. `business-invoices.md`) contain React/React-Query code samples describing an *intended* integration pattern, but no such code exists in the repo. **Conclusion: Angular version, folder structure, routing, components, state management, dashboard UI, and existing billing/report pages do not apply — there is nothing to inventory. ShopLedger's entire frontend is a from-scratch build**, informed only by the REST API contracts this backend already exposes.

### 1.3 Database

**Migrations.** Flyway, two migration sets: `db/migration/public/` (8 files, run once at app startup against the shared `public` schema) and `db/migration/tenant/` (19 files, replayed against each new tenant's dedicated schema by `TenantFlywayMigrationService` at signup).

*Public schema (8 migrations):* `tenants`, `permissions`, normalized `plans`/`plan_pricing`/`plan_features`/`plan_limits`, permission seed data, plan seed data, `platform_admins` (+ seeded super admin), `tenant_onboarding`, `audit_logs`.

*Tenant schema (19 migrations, retail-relevant ones in bold):* `roles`, `users`, `refresh_tokens`, `role_permissions`, `subscriptions`, `usage_records`, `usage_summaries`, `invoices` (platform billing), `invoice_line_items`, `payments` (platform billing), Quartz job tables, role seed data, role-permission seed data, **`customers`**, **`products`**, **`business_invoices`**, **`business_invoice_items`**, **`business_payments`**, `outbox_events`.

The bolded five tables — `customers`, `products`, `business_invoices`, `business_invoice_items`, `business_payments` — are the direct retail-billing seed for ShopLedger and are already structurally separate from the SaaS/platform billing tables.

**Schema design.** Every `AuditableEntity`-backed table shares: `id BIGSERIAL PK`, `deleted BOOLEAN` + `deleted_at` (soft delete, enforced via Hibernate `@SQLDelete`/`@SQLRestriction`), `version BIGINT` (optimistic locking), `created_at/updated_at/created_by/updated_by`. Key retail tables:
- `customers` — name, email, phone, address, `gstin` (optional), `razorpay_customer_id`. No unique constraint on email (multiple customers can share one), partial indexes on email/name/razorpay id (all `WHERE deleted=false`).
- `products` — name, description, price (12,2), tax_percentage (5,2), `hsn_sac_code`, unit, `is_active`. No unique constraint on name; partial index on `name WHERE is_active AND NOT deleted`.
- `business_invoices` — `invoice_number` (unique, format `BINV-{tenantId}-{YYYYMM}-{seq}`), `customer_id` FK, `status` (DRAFT/OPEN/PAID/VOID), subtotal/tax/total, currency, due_date, paid_at, pdf_url, razorpay_payment_link_id. Partial indexes on customer_id, status, due_date (`WHERE status='OPEN'`), created_at DESC.
- `business_invoice_items` — invoice_id FK, `product_id` (**no FK, deliberate** — see below), description, quantity, unit_price, tax_percentage, tax_amount, total (all snapshotted at creation time).
- `business_payments` — invoice_id FK, amount, currency, method (CARD/UPI/NETBANKING/BANK_TRANSFER — no CASH; cash is recorded as BANK_TRANSFER + a note), notes, paid_at, razorpay_payment_link_id. Supports partial/multiple payments per invoice.

**Relationships.** Real FKs: `business_invoices.customer_id → customers.id`, `business_invoice_items.invoice_id → business_invoices.id`, `business_payments.invoice_id → business_invoices.id`. **Deliberately no FK:** `business_invoice_items.product_id → products.id` — so deactivating/deleting a product never blocks or cascades into historical invoice lines (price/tax are snapshotted instead). Cross-schema references (e.g. `role_permissions.permission_id → public.permissions.id`, `subscriptions.plan_id → public.plans.id`) are **logical only** — PostgreSQL cannot enforce an FK across schemas, so referential integrity there is entirely application-code-enforced. No tenant-schema table carries a `tenant_id` column for row-level scoping (`usage_records.tenant_id` is a documented exception) — isolation is 100% via `search_path`/schema, not row filtering.

**Indexes.** Generally well-designed: mostly *partial* indexes tied to the soft-delete flag and to hot query predicates (`status='OPEN'`, `is_active=true`). One gap found: `role_permissions.permission_id` (the cross-schema reference) has no index at all, only `role_id` does — a candidate to fix if ShopLedger ever needs bulk "which roles have permission X" queries.

### 1.4 Infrastructure

**Docker.** Multi-stage `Dockerfile` (`eclipse-temurin:21-jdk-alpine` builder → `21-jre-alpine` runtime), no non-root user, no healthcheck, no JVM tuning flags. `docker-compose.yml` (v3.8) defines `app` (built from local Dockerfile, port 8080), `postgres` (`postgres:16-alpine`, hardcoded credentials, healthcheck via `pg_isready`), `redis` (`redis:7-alpine`, healthcheck via `redis-cli ping`) — no `mailhog` despite the README claiming one. DB credentials are hardcoded in `docker-compose.yml` rather than sourced from `.env`.

**Environment variables** (`.env.example`): grouped `SPRING`, `DATABASE`, `REDIS`, `JWT` (secret/expiration/refresh-expiration/issuer/audience), `RAZORPAY` (key id/secret/webhook secret), `MAIL` (Brevo SMTP host/port/user/pass/from), `APP` (frontend URL, CORS origins, admin password).

**Application config.** Three profiles: base `application.yaml` (Flyway disabled by default, Hikari pool 10, Redis cache, Quartz JDBC job store, JWT/Razorpay/CORS all env-driven, springdoc at `/swagger-ui.html`), `application-dev.yaml` (Flyway **enabled**, verbose SQL/Hibernate logging, file logging), `application-prod.yaml` (`ddl-auto: none`, Hikari pool 20, Quartz clustered, secure cookies, WARN-level logging). **Notable:** prod does not override Flyway's `enabled: false` from the base file — meaning only `dev` actually auto-runs migrations; prod migration execution must happen another way (not evidenced in the repo). No `management.*` actuator exposure is configured anywhere, so only Spring Boot's default (`/actuator/health`) is exposed — despite docs describing a full Prometheus/Grafana stack.

**Build process.** Plain Maven build (`spring-boot-maven-plugin`, `maven-compiler-plugin` with strict Lombok-before-MapStruct processor ordering, a standalone `flyway-maven-plugin` for CLI migrations hardcoded to `localhost:5432/mtbs_db`). **No jacoco, no checkstyle/spotless, no docker-maven-plugin/jib** — despite docs claiming SonarQube compliance and 82% coverage.

**Testing.** 16 test files (~3,600 lines): unit tests (Mockito-style, `AuthServiceTest`, `RoleServiceTest`, `InvoiceServiceTest`, `PaymentServiceTest`, `ProrationServiceTest`, `BusinessInvoiceServiceTest`, `CustomerServiceTest`, `TenantFlywayMigrationServiceTest`), three `@SpringBootTest` integration tests (`BillingFlowIntegrationTest`, `WebhookIntegrationTest`, `MultiTenancyIntegrationTest`), an `ArchitectureRulesTest` (ArchUnit-style layering rules), an app-context smoke test, and test-support fixtures (`TestDataBuilder`, `TestSchemaHelper`). `SubscriptionServiceTest` is an **empty placeholder** (4 lines, no test methods). Despite `testcontainers` being a declared dependency, **no test class actually uses `@Testcontainers`/`@Container`** — the `test` profile instead requires a manually-provisioned local Postgres database (`mtbs_test`) per comments in `application-test.yaml`. This directly contradicts the testing-strategy doc's description of an H2 in-memory + Testcontainers setup.

**CI/CD.** **None exists.** No `.github/workflows/` directory, no workflow YAML anywhere. The only content under `.github/` is an AI-tooling scaffold (`modernize/java-upgrade/hooks/...`) that is itself `.gitignore`d. Any build/test/deploy pipeline for ShopLedger starts from zero.

---

## STEP 2 — Feature Comparison

| ShopLedger Feature | Existing | Reusable | Needs Modification | Build New |
|---|:---:|:---:|:---:|:---:|
| Multi Client | Partial* | ✅ | — | — |
| Shop Settings | ✗ | — | Minor† | ✅ |
| Customer Management | ✅ | ✅ | Minor | — |
| Simple Bill | Partial‡ | ✅ | ✅ | — |
| Bill History | ✅ | ✅ | — | — |
| Reprint Bill | Partial§ | ✅ | Minor | — |
| PDF Bill | ✅ | ✅ | ✅ | — |
| Thermal Bill | ✗ | — | — | ✅ |
| Dashboard | ✗ | — | — | ✅ |
| Daily Report | ✗ | — | ✅ | — |
| Monthly Report | ✅ | ✅ | — | — |
| Outstanding Report | ✅ | ✅ | Minor | — |
| Customer Report | ✗ | — | — | ✅ |
| Role Management | ✅ | ✅ | — | — |
| User Management | ✅ | ✅ | — | — |

\* *"Multi Client" is ambiguous and worth resolving with the product owner (see §2.1 below) — the existing multi-tenant SaaS isolation (one client = one schema) is fully built and battle-tested, but "one owner managing several shop branches under one login" is not modeled at all.*
† *A `ShopSettings`-style entity must be built new, but it slots into the existing `Tenant`/tenant-schema pattern with minimal ceremony.*
‡ *`BusinessInvoice`/`BusinessInvoiceItem` already implement a DRAFT→OPEN→PAID/VOID invoice with line items and tax — the data model and PDF pipeline are reusable, but the workflow needs simplifying for point-of-sale-style instant billing.*
§ *PDF regeneration on-demand already effectively supports reprint; a reprint audit trail (who/when) would need to be added.*

### 2.1 Decision point: what does "Multi Client" mean for ShopLedger?

The codebase supports exactly one interpretation well and not the other at all:

- **Interpretation A — many independent shop-owner clients, each fully isolated** (typical multi-tenant SaaS): **already built.** Schema-per-tenant gives each client a hard, PostgreSQL-schema-level data boundary; JWT + `TenantContext` + Hibernate multitenancy routing already handle this end-to-end.
- **Interpretation B — one shop owner manages multiple shop locations under a single login**: **not built.** There is no "Client"/"Owner" entity distinct from `Tenant`; a `Tenant` *is* a business, 1:1 with a schema. Today, an owner wanting two shops would need two separate signups (two schemas, two logins). Supporting this would require either (a) a new parent `Client`/`Owner` entity with a one-to-many to `Shop` (repurposing today's `Tenant` as `Shop`), or (b) a `Shop` sub-entity nested inside one tenant schema if all locations should share one login/subscription.

This is a product decision, not a technical one — flagging it now avoids rework later.

---

## STEP 3 — Code Quality Review

**Architecture quality: good.** Consistent vertical-slice module structure (`controller/service/repository/entity/dto/mapper` per feature), a shared `AuditableEntity` base class (soft delete + optimistic locking + automatic audit stamps) applied almost universally, hexagonal ports/adapters in the notification module, MapStruct for entity↔DTO mapping, and a centralized exception/response envelope. The `business` module (customer/product/invoice/payment/report) is essentially a ready-made template for ShopLedger's own domain — this is the strongest asset in the repository.

**Documentation vs. reality gap (flag for the team).** The `docs/` folder describes a materially more mature system than what exists: distributed tracing (Sleuth/Zipkin), Prometheus/Grafana alerting, a working GitHub Actions pipeline, H2 + Testcontainers test infrastructure, an 82%+ verified coverage number, and a React frontend are all documented as if shipped, and none of them exist in the repository. This isn't just stale docs — it risks misleading future contributors (including an AI assistant) into building on assumptions that don't hold. **Recommendation: treat `docs/` as a design-intent reference only, and re-verify any specific claim against source before relying on it — this document did exactly that.**

**Concrete technical debt found:**
- **JDK version mismatch** — `pom.xml` targets Java 17; `Dockerfile` builds/runs on JDK/JRE 21.
- **No CI/CD pipeline** — no GitHub Actions or any other automation; ShopLedger needs this built from scratch.
- **Testcontainers is dead weight** — declared as a dependency but unused; integration tests require a manually-provisioned local Postgres, making CI/onboarding harder than the docs suggest.
- **No coverage tooling** — no jacoco/checkstyle/spotless configured; the docs' 82% coverage figure is unverifiable and likely fabricated.
- **Thin, partially incomplete test suite** — 16 test files for ~320 classes; `SubscriptionServiceTest` is an empty placeholder with zero test methods.
- **Prod Flyway gap** — `application-prod.yaml` doesn't override the base `flyway.enabled: false`, so only the `dev` profile auto-runs migrations; how prod migrations actually get applied isn't evidenced anywhere in the repo.
- **Cross-schema references have no DB-level integrity** — `role_permissions.permission_id`, `subscriptions.plan_id`, and (by design) `business_invoice_items.product_id` are enforced only in application code. This is an inherent trade-off of the schema-per-tenant design (not a bug), but ShopLedger's team should know referential integrity for these columns depends entirely on service-layer discipline. `role_permissions.permission_id` also has no index.
- **Self-contradicting migration** — the project's own multi-tenancy documentation states tenant migrations must never reference other schemas, yet `V13__seed_role_permissions.sql` does exactly that (`CROSS JOIN public.permissions`). Works today because migrations run in a fixed order, but it's a documented rule the code itself breaks.
- **Minor response-format inconsistency** — `JwtAuthenticationFilter`'s 401 responses are hand-built JSON, bypassing the otherwise-centralized `GlobalExceptionHandler`/`ApiResponse` envelope.
- **Docker hardening gaps** — no non-root user, no healthcheck, no JVM memory tuning in the `Dockerfile`; DB credentials hardcoded in `docker-compose.yml` rather than sourced from `.env`.

**Security: solid foundation, worth keeping.** BCrypt password hashing, JWT with Redis-cached token versioning for instant revocation without a blocklist table, Redis-cached RBAC permission resolution, hard tenant isolation via PostgreSQL schema boundaries (not row-level filtering, which the project's own ADR reasons through convincingly), consistent `@PreAuthorize` usage, deliberate stateless-JWT CSRF posture. No SQL injection risk observed in the sampled code (parameterized JPA/JPQL throughout, no raw string-concatenated queries found).

**Performance: reasonable, unverified at scale.** Hikari pool sizing is sane per environment (10 dev / 20 prod). Indexing is thoughtful (partial indexes matched to real query predicates). The entities doc claims N+1 query risks exist in places (e.g. user↔role loading without `@EntityGraph`) — this is a documented self-admitted issue, not independently re-verified line-by-line in this pass, but plausible given the lazy-loading defaults observed and worth a targeted look before ShopLedger scales invoice/customer list views.

**Database: sound design, one real gap.** Soft-delete + optimistic locking + audit columns applied consistently. Snapshotting product price/tax onto invoice line items at creation time is exactly the right pattern for a billing system and should be kept verbatim for ShopLedger. The one real gap: no `tenant_id` column exists on tenant-schema tables (isolation is schema-only), which is fine for "one tenant = one shop" but becomes a design question the moment ShopLedger needs "one shop, multiple counters/branches" data partitioning within a single schema (see §2.1).

---

## STEP 4 — Migration Roadmap

### KEEP (use as-is, or with only cosmetic renaming)

- Schema-per-tenant multi-tenancy plumbing: `SchemaBasedMultiTenantConnectionProvider`, `CurrentTenantIdentifierResolverImpl`, `TenantContext`, `JpaConfig` — this is the "Multi Client" foundation (Interpretation A, §2.1).
- JWT authentication stack: `JwtAuthenticationFilter`, `JwtTokenProvider`, `UserPrincipal`, `SecurityConfig`, `TokenVersionCacheService`, `PermissionCacheService`.
- RBAC: `Role`, `Permission`, `RolePermission` entities and `RoleController`/`UserController`/`PermissionController` — directly reusable for ShopLedger's Role Management and User Management requirements with essentially no changes.
- Exception handling framework: `BaseException`, `ErrorCode`, `GlobalExceptionHandler`, `ApiResponse`.
- Base entity pattern: `BaseEntity`/`AuditableEntity` (soft delete, optimistic locking, audit stamps) — apply to every new ShopLedger entity.
- `Customer` entity/module (`business/customer/*`) — Customer Management, as-is.
- `Product` entity/module (`business/product/*`) — including the price/tax snapshot pattern, as-is.
- Flyway migration tooling and the public/tenant migration split.
- MDC logging/correlation infrastructure (`MdcLoggingFilter`, `MdcSecurityEnrichmentFilter`).
- `docker-compose.yml` base services (Postgres, Redis) — after fixing the credential-sourcing and JDK version issues noted in §3.
- Bean Validation usage pattern on request DTOs.

### MODIFY

- `BusinessInvoice` / `BusinessInvoiceItem` / `BusinessPayment` → adapt into ShopLedger's Bill/BillItem/BillPayment: simplify the DRAFT→OPEN workflow for point-of-sale-style instant billing (keep it optional for tab/credit-account sales), decouple India-specific GST fields from being assumed defaults (they're already optional at the schema level, but UI/validation should treat them as configurable), rename invoice numbering away from `BINV-*`, and add a reprint audit trail (who/when).
- `BusinessInvoicePdfService` → retarget the PDF layout from a formal "TAX INVOICE" to a configurable shop receipt/invoice with logo and shop-settings header; the iText pipeline and pattern stay.
- `BusinessReportService` → extend with a Daily Report (same aggregation pattern as the existing Monthly Summary, narrowed to a day) and a Customer Report (group the existing outstanding/revenue queries by `customerId` instead of by invoice).
- `Tenant` entity → extend with shop-profile fields (address, logo, GSTIN/tax id, currency, invoice numbering prefix), or introduce a dedicated `ShopSettings` entity in a 1:1 relationship with `Tenant` (recommended, to keep `Tenant` itself lean).
- `pom.xml` / `Dockerfile` → reconcile the Java 17 vs. JDK 21 mismatch; wire the already-declared Testcontainers dependency into actual test execution.
- `TenantOnboarding` (KYC + Razorpay-subscription-payment signup wizard) → this is heavy B2B SaaS onboarding; simplify drastically for a shop owner signing up, unless ShopLedger itself will be sold as a paid subscription with a similar KYC/payment step (see next section).

### REMOVE (or defer — not needed for ShopLedger's core V1 scope)

- `billing` module in full (Subscription, platform `Invoice`/`Payment`, Proration, Usage metering, `FeatureGateAspect`/`UsageTrackingAspect`, Razorpay webhook/gateway, `Plan`/`PlanPricing`/`PlanFeature`/`PlanLimit`) — this is MTBS's own mechanism for charging *tenants* a subscription fee to use the platform. It is unrelated to a shop billing *its own* customers.
  - **Decision point:** if ShopLedger itself will be monetized as a paid SaaS product (shop owners pay a subscription to use ShopLedger), this entire module is the right foundation to keep and rebrand rather than build from scratch — flag this to the product owner before removing it.
- Billing-cycle Quartz jobs (`BillingCycleJob`, `SubscriptionCancelJob`, `SubscriptionExpiryJob`, `TrialEndingSoonJob`, `TrialExpiryJob`, `PaymentRetryJob`) — tied to the `billing` module above; remove together with it (or keep together, per the same decision).
- SaaS-specific notification templates (plan upgraded, trial ending, etc.) — remove the *content*, keep the notification orchestrator/port architecture (`notification/application`, `notification/port`) as infrastructure.
- Cross-tenant SaaS admin metrics (`AdminMetricsService` — MRR, tenants-by-plan, etc.) — not relevant to a shop-billing product; a much simpler platform-ops view (if any) can be built later if ShopLedger needs it.
- `razorpayCustomerId`/`razorpayPaymentLinkId` coupling on `Customer`/`BusinessInvoice` — keep only if ShopLedger wants online/link-based customer payments; otherwise strip for a V1 focused on cash/local in-person payments.

### BUILD NEW

- **Entire frontend** — no frontend exists in this repository at all (see §1.2). Every screen (dashboard, billing, customer, reports, settings) starts from zero, informed by the REST contracts this backend already exposes.
- **Shop Settings** module (name, address, logo, tax id, currency, invoice numbering, printer configuration).
- **Thermal bill printing** — no ESC/POS or printer-width receipt logic exists anywhere; this is a from-scratch build, likely as a new service alongside `BusinessInvoicePdfService`.
- **Shop sales Dashboard** — today's sales, top items, outstanding at a glance; nothing like this exists (only a SaaS-subscription dashboard and a cross-tenant platform-admin metrics view, neither retail-shaped).
- **Daily Report** and **Customer Report** — new report methods on top of the existing `BusinessReportService` aggregation patterns (listed under MODIFY above since the underlying service and query patterns are reused, but the reports themselves are new).
- **Multi-shop-per-owner hierarchy**, if Interpretation B from §2.1 is what "Multi Client" actually means for ShopLedger — not modeled at all today.
- **Reprint audit trail** (who reprinted a bill, and when).
- **CI/CD pipeline** — none exists today; needs to be built for ShopLedger from the ground up (test execution, build, and — once decided — deployment).

---

*This document is an analysis artifact only. No source files, migrations, or configuration in this repository were modified as part of producing it.*
