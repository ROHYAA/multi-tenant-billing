---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - architecture
  - system
  - modules
  - multitenancy
  - spring-boot
  - postgresql
  - redis
  - payment-gateway
related_documents:
  - ./request-flow.md
  - ./event-flow.md
  - ../02-multi-tenancy/multi-tenancy-strategy.md
---

# System Design

## Executive Summary

The **Multi-Tenant Billing System (MTBS)** is a Spring Boot 3.4.3 SaaS platform that enables independent tenants to manage subscriptions, generate invoices, process payments, and bill their own customers. MTBS enforces hard tenant isolation via PostgreSQL schema-per-tenant multitenancy, routes HTTP requests through a sophisticated filter chain, and guarantees reliable event delivery via the transactional outbox pattern. The system is organized into 8 separate modules (app, auth, admin, billing, tenant, business, notification, shared) with clear responsibilities. Without this architecture, a SaaS platform would risk data leakage across tenants, lose events on crashes, and struggle to support thousands of concurrent billing operations.

---

## Context / Problem

### Why Modular Architecture?

Each module has one reason to change:

| Module | Responsibility | Example Change |
|--------|-----------------|-----------------|
| `app` | Startup, configuration, filters, health | Enable new filter, change actuator endpoints |
| `auth` | JWT, RBAC, user/role management | Add OAuth2, implement SSO, change token duration |
| `billing` | Platform billing (tenant pays MTBS) | Add proration, support multi-currency, new payment gateway |
| `tenant` | Tenant onboarding, plans, provisioning | Add KYC verification step, new plan types, schema versioning |
| `business` | Business billing (tenant bills customers) | Add invoice amendments, new reporting, dunning flow |
| `notification` | Email delivery | Change email provider, new templates, SMS channel |
| `admin` | Cross-tenant operations, audit logs | New admin features, compliance reports, tenant management |
| `shared` | Common entities, exceptions, utilities | New exception type, updated base entity, shared cache |

### Why Schema-Per-Tenant Isolation?

Three alternatives were considered:

1. **Shared Schema (Row-Level Security)** — All tenants in public schema, RLS policies filter rows
   - ✅ Pros: Single database, easy to migrate
   - ❌ Cons: RLS bugs cause data leakage; one bad query exposes all tenants; no query isolation

2. **Database-Per-Tenant** — Separate PostgreSQL database for each tenant
   - ✅ Pros: Maximum isolation, easy to export tenant data
   - ❌ Cons: Cannot do cross-tenant queries (admin dashboard), connection pool explosion, backup complexity

3. **Schema-Per-Tenant (CHOSEN)** — Each tenant gets PostgreSQL schema (s_1, s_2, etc.)
   - ✅ Pros: Hard database isolation, single connection, easy cross-tenant queries via public schema, schema isolation at OS level, can run migrations per schema
   - ✅ Pros: Hibernate SCHEMA multitenancy mode is designed for this
   - ✅ Cons: Schema provisioning latency (~200ms), connection pool must support many schemas

We chose schema-per-tenant because it balances isolation, manageability, and Hibernate support.

### Why Transactional Outbox Pattern?

**Problem**: If we publish domain events immediately after saving a business entity, and the process crashes before confirmation, the event is lost.

```
❌ WRONG:
   1. Invoice.save() 
   2. publish(InvoiceGeneratedEvent)   ← If process dies here, event lost forever
   3. HTTP 200 response
```

**Solution**: Save events and business data in the same transaction. Async scheduler publishes later.

```
✅ CORRECT:
   1. BEGIN TRANSACTION
      Invoice.save()
      OutboxEventPublisher.save(InvoiceGeneratedEvent)  ← Same transaction
   2. COMMIT
   3. HTTP 202 Accepted (event scheduled for delivery)
   4. OutboxEventProcessor (scheduled every 5s) publishes from outbox → ApplicationEventPublisher
```

This guarantees at-least-once event delivery, and idempotency keys prevent duplication.

---

## Dependencies

### Inbound (No Direct Requests to System Design)
- All HTTP requests → pass through filter chain defined here
- Spring Boot configuration → scans packages defined here
- Database initialization → uses Hibernate multitenancy setup defined here

### Outbound (System Design Depends On)
- **PostgreSQL** — Database; schema-per-tenant storage
- **Hibernate 6.x** — ORM; multitenancy provider, connection routing
- **Redis** — Cache layer; stores slug→tenantId, schema names, token versions, permissions
- **Razorpay** — Payment processor; 2-step payment flow
- **Quartz** — Job scheduler; 8 jobs (see [scheduler-jobs.md](../09-jobs/scheduler-jobs.md))
- **Spring Security** — Authentication framework; filters, SecurityContext, @PreAuthorize
- **Flyway** — Migration tool; public schema (8 migrations) + tenant schemas (20 migrations per)

### Configuration
- `application.yaml` — Default configuration (dev/test)
- `application-prod.yaml` — Production overrides (connection pools, logging level)
- `spring.datasource.url` — PostgreSQL connection string
- `spring.jpa.hibernate.ddl-auto` — Set to `validate` (migrations run via Flyway)
- `spring.redis.host`, `spring.redis.port` — Redis connection
- `mtbs.razorpay.key-id`, `mtbs.razorpay.key-secret` — Razorpay credentials
- `mtbs.scheduler.enabled` — Enable/disable Jobs (default: true)
- `mtbs.observability.mdc-enabled` — MDC logging context (default: true)

---

## Design / Implementation

### Package Structure

```
com.mtbs/
├── app/              [Application Core]
│   ├── config/       [Spring configuration beans]
│   ├── filter/       [HTTP request filters]
│   ├── health/       [Health check endpoints]
│   └── exception/    [Global exception handler]
│
├── auth/             [Authentication & Authorization]
│   ├── entity/       [User, Role, Permission, RefreshToken, PlatformAdmin]
│   ├── repository/   [Spring Data repositories]
│   ├── service/      [AuthService, UserService, RoleService, PermissionService]
│   ├── security/     [JwtTokenProvider, JwtAuthenticationFilter, UserPrincipal]
│   ├── controller/   [AuthController, AdminAuthController, UserController, RoleController]
│   └── dto/          [LoginRequest, AuthResponse, UserResponse, RoleResponse]
│
├── admin/            [Platform Administration - Cross-Tenant]
│   ├── service/      [AdminTenantService, AdminUserService, AdminMetricsService, AuditLogService]
│   ├── controller/   [AdminTenantController, AdminUserController, AdminMetricsController, AuditLogController]
│   └── dto/          [AdminTenantListResponse, AdminMetrics, AuditLogResponse]
│
├── tenant/           [Tenant Onboarding & Plans]
│   ├── entity/       [Tenant, Plan, TenantOnboarding]
│   ├── service/      [TenantService, PlanService, TenantFlywayMigrationService, OnboardingService]
│   ├── controller/   [TenantController, OnboardingController, PlanController]
│   ├── config/       [FlywayConfig]
│   └── dto/          [TenantResponse, PlanResponse, OnboardingStatusResponse]
│
├── billing/          [Platform Billing - Tenant Pays MTBS]
│   ├── entity/       [Subscription, Invoice, InvoiceLineItem, Payment, UsageRecord]
│   ├── repository/   [SubscriptionRepository, InvoiceRepository, PaymentRepository]
│   ├── service/      [SubscriptionService, InvoiceService, PaymentService, InvoicePdfService]
│   ├── scheduler/    [BillingCycleJob, PaymentRetryJob, OutboxEventProcessor]
│   ├── gateway/      [PaymentGatewayPort, RazorpayPaymentGateway]
│   ├── event/        [BillingEventPublisher, OutboxEventPublisher]
│   ├── controller/   [SubscriptionController, InvoiceController, PaymentController, RazorpayWebhookController]
│   └── dto/          [SubscriptionResponse, InvoiceResponse, PaymentResponse]
│
├── business/         [Business Billing - Tenant Bills Customers]
│   ├── customer/     [Customer, CustomerService, CustomerController, CustomerResponse]
│   ├── product/      [Product, ProductService, ProductController, ProductResponse]
│   ├── invoice/      [BusinessInvoice, BusinessInvoiceService, BusinessInvoiceController]
│   ├── payment/      [BusinessPayment service for customer payment tracking]
│   └── report/       [BusinessReportService, revenue/outstanding reports]
│
├── notification/     [Email Delivery Service]
│   ├── service/      [NotificationService — sends emails via Thymeleaf templates]
│   ├── listener/     [Event listeners that trigger email sends]
│   └── config/       [EmailTemplateConfig — template registry]
│
└── shared/           [Shared Utilities & Common Classes]
    ├── entity/       [BaseEntity, AuditableEntity — JPA base classes]
    ├── enums/        [Status, BillingCycle, Currency, InvoiceStatus, PaymentStatus, SubscriptionStatus]
    ├── exception/    [BaseException, ResourceException, AuthException, PaymentException, TokenException]
    ├── multitenancy/ [TenantContext (ThreadLocal), TenantContextHolder, CurrentTenantIdentifierResolverImpl]
    ├── event/        [DomainEvent, OutboxEvent, AuditLogEvent, BillingEvent, AuthEvent]
    ├── annotation/   [FeatureGate, TrackUsage — aspect-driven cross-cutting]
    └── constant/     [ApiConstants, CookieConstants, SchemaConstants, SecurityConstants]
```

### Multitenancy Architecture

**How Request-to-Schema Routing Works:**

```
1. HTTP Request arrives
   ↓
2. JwtAuthenticationFilter.doFilterInternal()
   ├─ Extract JWT from cookies
   ├─ Validate signature + expiry
   ├─ Extract claims: userId, tenantId, roleId, tokenVersion
   ├─ Call SchemaCacheService.resolveSchemaName(tenantId)  [Redis lookup]
   ├─ CREATE TenantContext (ThreadLocal)
   │   ├─ TenantContext.CURRENT_TENANT = tenantId
   │   └─ TenantContext.CURRENT_SCHEMA = "s_456" (example)
   ├─ Continue FilterChain
   │
3. Hibernate Multitenancy Resolver (CurrentTenantIdentifierResolverImpl)
   ├─ Intercepts: session.createQuery(), session.save(), etc.
   ├─ Calls: TenantContext.getCurrentSchema()  [Reads ThreadLocal]
   ├─ PostgreSQL: SET search_path TO "s_456", public
   │   (All table lookups now go to s_456.invoices instead of public.invoices)
   │
4. Repository method executes
   ├─ subscriptionRepository.findById(123)  [Uses search_path]
   └─ Returns invoice from correct schema
   
5. Response sent back
   
6. JwtAuthenticationFilter finally block
   ├─ TenantContext.clear()  [Wipe ThreadLocal]
   └─ Prevents next request in thread pool from seeing tenantId
```

**Critical ThreadLocal Rule**: If TenantContext is not cleared, the thread pool reuses the thread for the next request, and the next user would execute queries against the wrong schema — **silent data leakage**.

### Event-Driven Architecture

MTBS uses domain-driven events for decoupled communication:

```
Domain Service (SubscriptionService, InvoiceService, PaymentService)
  ↓
publish(DomainEvent)  [e.g., InvoiceGeneratedEvent]
  ↓
OutboxEventPublisher.save(event)  [Same transaction as business data]
  ↓
HTTP response sent (202 Accepted — event is scheduled)
  ↓
OutboxEventProcessor (scheduled every 5 seconds)
  ├─ Poll outbox_events table WHERE status=PENDING
  ├─ Lock row: SELECT * FROM outbox_events FOR UPDATE SKIP LOCKED
  ├─ Deserialize event
  ├─ ApplicationEventPublisher.publishEvent(event)
  │   ├─ Calls all @EventListener methods synchronously
  │   ├─ NotificationListener → send email
  │   ├─ AuditListener → log to audit_logs
  │   └─ AnalyticsListener → track metrics
  │
  ├─ If listeners succeed → UPDATE outbox_events SET status=PROCESSED
  ├─ If listeners fail → retry with exponential backoff (1s, 2s, 4s, 8s, ..., capped 5min)
  └─ Alert ops if max retries (5 attempts) exhausted
```

**Guarantees**: At-least-once delivery (events never lost). Idempotency keys prevent duplicates (same event published twice = idempotent listener logic).

### Two Billing Domains

MTBS enforces **two separate billing contexts**:

1. **Platform Billing (Public Schema)**
   - Tenant pays MTBS (you) for subscription
   - Entities: Subscription, Invoice (platform charges), Payment (platform receives)
   - Flow: BillingCycleJob → generates monthly invoice → tenant makes payment → mark as PAID → revenue recognized
   - Module: `com.mtbs.billing`

2. **Business Billing (Tenant Schema)**
   - Tenant bills their own customers
   - Entities: BusinessInvoice, BusinessInvoiceItem, BusinessPayment (customer pays tenant)
   - Flow: Tenant manually creates invoices → customers pay tenant → tenant records payment
   - Module: `com.mtbs.business`

**Isolation**: businessInvoiceRepository queries hit tenant schema. invoiceRepository queries hit public schema. No cross-schema queries (except admin dashboards).

### Configuration Layers

**Layer 1: Spring Annotations**

```java
@SpringBootApplication(scanBasePackages = "com.mtbs")
@EnableAsync
@EnableScheduling
public class MultiTenantBillingSystemApplication { }
```

- `scanBasePackages = "com.mtbs"` — only load MTBS beans, not framework samples
- `@EnableAsync` — allows async event listeners
- `@EnableScheduling` — activates @Scheduled jobs

**Layer 2: Bean Configuration via `@Configuration` classes**

- `JpaConfig` — Hibernate multitenancy setup, entity scanning
- `RedisConfig` — Redis connection pool, ObjectMapper for serialization
- `QuartzConfig` — Job scheduler setup, job definitions
- `WebMvcConfig` — CORS, request/response interceptors
- `AsyncConfig` — Thread pool settings for @Async listeners

**Layer 3: application.yaml Properties**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mtbs
    username: postgres
    password: secret
  jpa:
    hibernate:
      ddl-auto: validate  # Flyway handles migrations
  redis:
    host: localhost
    port: 6379
  scheduler:
    pool:
      size: 4  # Quartz thread pool size

mtbs:
  razorpay:
    key-id: ${RAZORPAY_KEY_ID}
    key-secret: ${RAZORPAY_KEY_SECRET}
  observability:
    mdc-enabled: true
  scheduler:
    enabled: true
```

---

## Flow

### Request Lifecycle (Simplified)

```
┌────────────────────────────────────┐
│   HTTP Request (GET /api/invoices) │
└────────────────┬───────────────────┘
                 │
                 ▼
        ┌────────────────┐
        │  Tomcat (Port  │
        │     8080)      │
        └────────┬───────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ MdcLoggingFilter          │
    │ (Generate requestId,      │
    │  traceId, spanId)         │
    └────────────┬──────────────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ Spring Security Chain     │
    │ (Check if endpoint needs  │
    │  authentication)          │
    └────────────┬──────────────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ JwtAuthenticationFilter   │
    │ • Extract JWT             │
    │ • Validate claims         │
    │ • Set TenantContext       │
    │ • Resolve schema name     │
    │ • Build UserPrincipal     │
    └────────────┬──────────────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ MdcSecurityEnrichment     │
    │ (Add tenant/user to MDC)  │
    └────────────┬──────────────┘
                 │
                 ▼
         ┌───────────────┐
         │ @PreAuthorize │
         │ (Check role)  │
         └───────┬───────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ InvoiceController         │
    │ .listInvoices(pageable)   │
    │                           │
    │ InvoiceService            │
    │ .getAllInvoices(pageable) │
    │                           │
    │ InvoiceRepository         │
    │ .findAll(pageable)        │
    │ [Hibernate routes query   │
    │  to tenant schema via     │
    │  CurrentTenantResolver]   │
    └────────────┬──────────────┘
                 │
                 ▼
       ┌──────────────────┐
       │  PostgreSQL      │
       │  Database        │
       │  Schema: s_456   │
       │  SELECT * FROM   │
       │  invoices        │
       └────────┬─────────┘
                │
                ▼
       ┌──────────────────┐
       │ Result set       │
       │ (10 invoices)    │
       └────────┬─────────┘
                │
                ▼
    ┌───────────────────────────┐
    │ InvoiceMapper             │
    │ Convert to DTO            │
    │ InvoiceResponse[]         │
    └────────────┬──────────────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ GlobalExceptionHandler    │
    │ (No exception caught)      │
    └────────────┬──────────────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ HTTP 200 OK               │
    │ Content-Type: application/│
    │ json                      │
    │ Body: {items: [...]}      │
    │ X-Request-Id: abc123      │
    │ X-Trace-Id: xyz789        │
    └────────────┬──────────────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ JwtAuthenticationFilter   │
    │ finally block:            │
    │ TenantContext.clear()     │
    │ [Thread safe for reuse]   │
    └────────────┬──────────────┘
                 │
                 ▼
    ┌───────────────────────────┐
    │ Response sent to client   │
    └───────────────────────────┘
```

### Module Interaction Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                          HTTP Client                             │
└────────────────────────────┬──────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     AuthController [AUTH-37]                    │
│  POST /api/auth/login, /api/auth/signup, /api/auth/refresh      │
└──────────┬────────────────────────────────────────┬──────────────┘
           │                                        │
           ▼                                        ▼
   ┌──────────────────┐                  ┌──────────────────┐
   │  AuthService     │                  │ SignupService    │
   │  [AUTH-13]       │                  │ [AUTH-16]        │
   │                  │                  │                  │
   │ • login()        │                  │ • signup()       │
   │ • createToken()  │                  │ • createOwner    │
   │ • validateToken()│                  │ • provision      │
   └──────┬───────────┘                  └────────┬─────────┘
          │                                       │
          ▼                                       ▼
    ┌──────────────────────────────────────────────────────┐
    │        TenantService [TEN-10]                        │
    │        PlanService [TEN-11]                          │
    │        TenantFlywayMigrationService [TEN-13]         │
    └──────────────────┬───────────────────────────────────┘
                       │
                       ▼
    ┌──────────────────────────────────────────────────────┐
    │        Tenant Repository [TEN-7]                     │
    │        Plan Repository [TEN-8]                       │
    │        (queries: public schema)                      │
    └───────────────────┬────────────────────────────────┘
                        │
                        ▼
    ┌──────────────────────────────────────────────────────┐
    │   PostgreSQL Public Schema                           │
    │   Tables: tenants, plans, permissions,               │
    │   platform_admins, users, roles, audit_logs          │
    └──────────────────────────────────────────────────────┘

                                    ┌─────────────────────────┐
                                    │  BillingController [40] │
                                    │  /api/subscriptions,    │
                                    │  /api/invoices,         │
                                    │  /api/payments,         │
                                    │  /api/usage             │
                                    └────────────┬────────────┘
                                                 │
                                    ┌────────────┴────────────┐
                                    ▼                         ▼
                        ┌──────────────────────┐   ┌──────────────────────┐
                        │ SubscriptionService  │   │ InvoiceService [15]  │
                        │ [BIL-14]             │   │ PaymentService [16]  │
                        │                      │   │                      │
                        │ • create()           │   │ • generateInvoice()  │
                        │ • activate()         │   │ • markInvoicePaid()  │
                        │ • upgrade()          │   │ • voidInvoice()      │
                        │ • cancel()           │   │                      │
                        │ • prorate()          │   │                      │
                        └──────────┬───────────┘   └────────────┬─────────┘
                                   │                            │
                                   └────────────┬───────────────┘
                                                │
                        ┌───────────────────────▼────────────────────────┐
                        │ OutboxEventPublisher [BIL-35]                 │
                        │ save(event, aggregateId, aggregateType)       │
                        │ • Create OutboxEvent                          │
                        │ • Insert into outbox_events (same transaction)│
                        │                                               │
                        │ OutboxEventProcessor [BIL-30] (Quartz Job)   │
                        │ • Poll outbox_events WHERE status=PENDING    │
                        │ • Lock with FOR UPDATE SKIP LOCKED            │
                        │ • ApplicationEventPublisher.publishEvent()    │
                        │ • Listeners: Notification, Audit, Analytics  │
                        │ • Update status=PROCESSED                     │
                        └────────────────┬────────────────────────────┘
                                         │
                    ┌────────────┬───────┴────────┬─────────────┐
                    ▼            ▼                ▼             ▼
            ┌─────────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────────┐
            │ Notification│ │ AuditListener│ │Analytics │ │RefundService │
            │ Listener    │ │[SHD-41]      │ │ Listener │ │(event-driven)│
            │             │ │              │ │          │ └──────────────┘
            │ Send Email  │ │ Log to public│ │Track     │
            │ (Thymeleaf) │ │ schema       │ │metrics   │
            │             │ │ (audit_logs) │ │(Redis)   │
            │             │ │              │ │          │
            └─────────────┘ └──────────────┘ └──────────┘

                    ┌──────────────────────────────────────┐
                    │  Tenant Schema (s_456)               │
                    │  Tables: invoices, subscriptions,    │
                    │  invoice_line_items, payments,       │
                    │  usage_records, outbox_events        │
                    └──────────────────────────────────────┘

                    ┌──────────────────────────────────────┐
                    │  BusinessController [BUS-4, etc.]    │
                    │  /api/customers, /api/products,      │
                    │  /api/business-invoices,             │
                    │  /api/business-payments              │
                    └──────────────────────────────────────┘
```

---

## Code References

| Class | Tag | Method/Purpose | Role |
|-------|-----|----------------|------|
| `MultiTenantBillingSystemApplication` | [APP-1] | main() | Spring Boot entry point |  
| `JpaConfig` | [APP-2] | hibernatePropertiesCustomizer() | Registers multitenancy provider + resolver |
| `MdcLoggingFilter` | [APP-8] | doFilter() | Generate & clear MDC context (requestId, traceId, spanId) |
| `MdcSecurityEnrichmentFilter` | [APP-9] | doFilter() | Add tenantId, userId, role to MDC from SecurityContext |
| `GlobalExceptionHandler` | [APP-10] | @ExceptionHandler methods | Convert exceptions to JSON responses + HTTP status |
| `JwtTokenProvider` | [AUTH-29] | generateToken(), validateToken(), getTokenVersionFromToken() | JWT lifecycle |
| `JwtAuthenticationFilter` | [AUTH-30] | doFilterInternal() | Extract JWT, set TenantContext, build UserPrincipal |
| `UserPrincipal` | [AUTH-32] | getAuthorities() | Spring Security principal with role authorities |
| `TenantContext` | [SHD-26] | setTenantId(), setCurrentSchema(), clear() | ThreadLocal tenant routing |
| `CurrentTenantIdentifierResolverImpl` | [SHD-29] | resolveCurrentTenantIdentifier() | Hibernate callback: returns schema name for SET search_path |
| `SchemaBasedMultiTenantConnectionProvider` | [SHD-28] | getConnection() | Hibernate callback: routes connection to correct schema |
| `Tenant` | [TEN-1] | public schema entity | Represents a tenant organization |
| `Plan` | [TEN-2] | public schema entity | Subscription plans (Free, Pro, Enterprise) |
| `TenantFlywayMigrationService` | [TEN-13] | createSchemaAndMigrate() | Provision tenant schema + migrations |
| `Subscription` | [BIL-1] | billing domain entity | Tenant's subscription to a Plan |
| `Invoice` | [BIL-2] | billing domain entity | Monthly charge from tenant subscription |
| `InvoiceLineItem` | [BIL-3] | billing domain entity | Line on invoice (subscription, overage, tax, discount) |
| `OutboxEventPublisher` | [BIL-35] | save() | Persist events to outbox table in same transaction |
| `OutboxEventProcessor` | [BIL-30] | processOutbox() | Scheduled job: publish pending outbox events |
| `Customer` | [BUS-1] | tenant schema entity | End customer of tenant (for business invoices) |
| `BusinessInvoice` | [BUS-15] | tenant schema entity | Invoice from tenant to their customer |
| `BusinessPayment` | [BUS-26] | tenant schema entity | Payment from tenant's customer |

---

## Rules / Constraints

1. **All Hibernate queries MUST respect TenantContext** — If TenantContext is not set before Hibernate executes a query, the query fails or returns wrong schema. Never bypass TenantContext by using raw JDBC or HQL with explicit schema names. Use repository methods only; they read TenantContext via CurrentTenantIdentifierResolverImpl.

2. **ThreadLocal MUST be cleared in finally block** — If TenantContext.clear() is in the try block, an exception can skip it, leaving the ThreadLocal set. Next request in thread pool inherits the tenantId, causing silent data leakage. Always: `try { filterChain.doFilter(); } finally { TenantContext.clear(); }`

3. **OutboxEvent MUST be persisted in same transaction as business data** — If rollback occurs after invoice is saved but before outbox event, the event is lost. Always: `BEGIN; invoice.save(); outboxEvent.save(); COMMIT;` in one @Transactional method.

4. **Domain event listeners MUST be idempotent** — OutboxEventProcessor can publish the same event twice (network failure, cluster restart). If listener sends email twice, customer sees duplicate email. Always check: "Did I already send email for this invoiceId?" before executing listener action. Use unique constraint on idempotency_key.

5. **Schema names MUST follow SchemaBasedMultiTenantConnectionProvider logic** — Currently all schemas are "s_{tenantId}" format (e.g., "s_456"). Changing the format requires updating CurrentTenantIdentifierResolverImpl. Schema names must be valid PostgreSQL identifiers (alphanumeric + underscore, max 63 chars).

6. **Redis cache keys MUST be prefixed by module** — Prevents collisions. E.g., "slug:tenant-xyz", "schema:456", "permissions:456:userId", "plan:789". If two modules use "tenant-123", cache key collision causes data leakage.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| JWT signature invalid | `TokenException` (extends `AuthException`) | 401 Unauthorized | Check token not tampered with; refresh token if expired |
| TenantContext not set (JwtAuthenticationFilter bypass) | `NullPointerException` when CurrentTenantIdentifierResolverImpl reads ThreadLocal | 500 Internal Server Error | Check filter order in SecurityConfig; JwtAuthenticationFilter must run before endpoint |
| Redis connection down (token version lookup fails) | `RedisConnectionException` in TokenVersionCacheService | 503 Service Unavailable | Cache miss; fall back to database lookup (slower path exists in code) or wait for Redis recovery |
| OutboxEvent record lock timeout (30s max on FOR UPDATE SKIP LOCKED) | OutboxEventProcessor skips locked records for 30s, then retries | N/A (internal) | Stale lock is auto-released; no manual action needed |
| Schema DDL migration fails (Flyway) | `FlywayException` during TenantFlywayMigrationService.createSchemaAndMigrate() | 500 Internal Server Error during signup | Schema already exists / migration syntax error; check logs; cleanup partial schema manually |
| Razorpay webhook signature verification fails | `PaymentException` in RazorpayPaymentGateway.verify() | 401 Unauthorized | Check HMAC secret is correct in application.yaml; verify webhook payload not tampered; log and alert |
| Invoice PDF generation timeout (Flying Saucer HTML-to-PDF) | `TimeoutException` in InvoicePdfService.generatePdf() | 504 Gateway Timeout | Retry endpoint; if persistent, check server resources (heap memory, disk space for temp files) |
| Duplicate foreign key in plan_limits (plan_id + tenant_id violates unique constraint) | `DataIntegrityViolationException` from PlanService | 409 Conflict | Plan already assigned to tenant; fetch existing plan instead of creating duplicate |
| No auditor present when saving AuditableEntity (SecurityUtils.getCurrentUserId() returns null) | `NullPointerException` or `DataIntegrityViolationException` (created_by not nullable) | 500 Internal Server Error | Request must pass through JwtAuthenticationFilter or @PreAuthorize; check @WithMockUser in tests |

---

## Edge Cases

- **Concurrency**: Multiple threads in thread pool processing same tenant's requests. Thread pool size matters. If pool has 10 threads and 10 tenants make simultaneous requests, each thread could use TenantContext for different tenant. As long as TenantContext is ThreadLocal, no collision. If context is moved to instance variable, threads interfere.

- **Timezone**: All timestamps are stored as `TIMESTAMPTZ` (UTC) in PostgreSQL. Tenant's timezone stored in Tenant entity. API returns UTC; frontend converts to tenant's local timezone. No "midnight" ambiguity because times are explicit (e.g., 2026-04-01T00:00:00+00:00).

- **Tenant Isolation**: Schema-per-tenant means a tenant cannot see another tenant's schema. Public schema is readable by all tenants (plan list, platform admin data visible to authorized users). Admin can query cross-tenant data if they have TENANT_MANAGE permission.

- **Empty State**: New tenant has no subscriptions, invoices, or payments. Queries return empty lists. BillingCycleJob skips tenants with no subscriptions (no invoice generation if nothing to bill).

- **Partial Payment**: Razorpay supports partial authorizations. If customer pays $50 of $100 invoice, payment.status=AUTHORIZED but amount != invoice.totalAmount. Current code assumes full payment; partial payment logic deferred.

- **Schema Rename**: PostgreSQL `ALTER SCHEMA s_123 RENAME TO s_456` is atomic. If needed for data redaction (GDPR), rename schema, update Tenant.schema_name, update Redis cache. Only admin operation after application pause.

---

## Known Issues / Limitations

1. **No automatic failover for Redis** — If Redis goes down, token versioning and schema resolution fall back to database queries (slower). No cluster mode configured; single Redis instance is a single point of failure. Sentinel mode not yet configured.

2. **Flyway migrations are per-environment** — Migrations differ between dev/test/prod (dev might have test seeds, prod has stricter constraints). Managing 3 different SQL migration sets is manual work. Automated migration testing framework would help.

3. **Quartz jobs are not guaranteed to be distributed** — In a multi-instance deployment, all instances run the same jobs. `BillingCycleJob` could run on both Instance A and Instance B, billing tenants twice. Needs distributed locking or job execution filtering.

4. **No multi-region support** — PostgreSQL replica-aware connection routing not configured. Standby database for read replicas would need explicit configuration.

5. **ObjectMapper configuration is global** — If a feature needs custom JSON serialization (e.g., special formatting for dates), changing `@JsonFormat` on a DTO affects all users of that DTO across modules. No per-module serialization strategies.

---

## Future Improvements

1. Implement Redis Sentinel/Cluster mode for high availability — Single Redis instance is bottleneck; Sentinel provides automatic failover.

2. Add job execution lock (Quartz plugin or database lock) to prevent duplicate billing in multi-instance setup — Distributed locking ensures only one instance runs BillingCycleJob.

3. Implement schema versioning & auto-migration strategy — Track schema version in Tenant entity; auto-detect migrations and apply them on startup.

4. Add feature flags service (Redis-backed) — Toggle features per tenant without deployment (e.g., enable business invoices for select tenants before GA release).

5. Implement cross-module event bus (Kafka/RabbitMQ) for async inter-module communication — Currently using Spring ApplicationEventPublisher (in-process only); external event bus enables audit service in separate service.

6. Add comprehensive tracing (Jaeger/DataDog) — MDC logging provides request correlation; distributed tracing shows call flow across service boundaries (if services are split later).

---

## Related Documents
- [authentication.md](../03-security/authentication.md) — JWT/RBAC security model
- [tenant-context-lifecycle.md](../02-multi-tenancy/tenant-context-lifecycle.md) — ThreadLocal tenant routing
- [multi-tenancy-strategy.md](../02-multi-tenancy/multi-tenancy-strategy.md) — Schema-per-tenant design decisions
- [request-flow.md](./request-flow.md) — HTTP request filter chain
- [outbox-pattern.md](./outbox-pattern.md) — Event delivery guarantees
- [event-flow.md](./event-flow.md) — Event architecture & listeners
- [invoice.md](../05-platform-billing/invoice.md) — Domain object example
- [subscription-lifecycle.md](../05-platform-billing/subscription-lifecycle.md) — Related Phase 2 document
- [payment-processing.md](../05-platform-billing/payment-processing.md) — Related Phase 2 document
- [authorization-rbac.md](../03-security/authorization-rbac.md) — Related Phase 2 document
- [schema-provisioning.md](../02-multi-tenancy/schema-provisioning.md) — Related Phase 2 document
