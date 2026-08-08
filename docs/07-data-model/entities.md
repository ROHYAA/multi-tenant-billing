---
version: 1.0
date: 2026-04-24
author: Manoj Pandi
status: Production Ready
tags:
  - data-model
  - entities
  - jpa
  - multitenancy
  - audit
related_documents:
  - ../01-architecture/system-design.md
  - ../02-multi-tenancy/multi-tenancy-strategy.md
  - ../05-platform-billing/subscription-lifecycle.md
  - ../05-platform-billing/invoice.md
  - ../06-api/error-handling.md
---

# Entities

## Executive Summary

The MTBS data model consists of ~20 JPA entities split across two scopes: **public schema** (tenant records, plans, permissions, audit logs, super-admins) and **tenant schemas** (users, roles, subscriptions, invoices, payments, usage). All entities extend `AuditableEntity` for automatic tracking of creation/modification timestamps and authors. Entities use soft-delete patterns, Hibernate multitenancy routing, and cascade behaviors to maintain data integrity across schema boundaries. Understanding the entity model is **critical for any data access, migration, or business logic work**.

---

## Context & Problem

### Why Entity Design Matters

JPA entities are more than database tables; they reflect business domain concepts and their relationships. Poor entity design leads to:

1. **N+1 query problems** — forgetting `@EntityGraph` results in dozens of queries instead of 1
2. **Cascade tragedies** — using `PERSIST` when `MERGE` is needed orphans data mid-update
3. **Soft-delete gotchas** — queries returning "deleted" records because `@SQLRestriction` isn't applied
4. **Cross-schema integrity violations** — FK constraints that reference wrong schema
5. **Audit trail gaps** — `@CreatedBy` missing because `AuditingEntityListener` isn't configured

### Schema Separation Design Decision

MTBS uses **schema-per-tenant** architecture, which means entities live in two distinct scopes with different lifecycle rules:

| Scope | Schema | Entities | Accessed By | Lifetime |
|-------|--------|----------|--------|----------|
| **Platform** | `public` | Tenant, Plan, Permission, PlatformAdmin, AuditLog | Platform admins + all tenants | Permanent (never deleted) |
| **Tenant** | `s_{tenantId}` (e.g., `s_456`) | User, Role, Subscription, Invoice, Payment, UsageRecord | Only tenant's users (via TenantContext) | Scoped to tenant lifetime |

**Critical consequence:** All tenant-scoped entities MUST be accessed AFTER `TenantContext.setTenantId()` and `TenantContext.setCurrentSchema()`. Accessing before results in queries hitting the **wrong schema**.


## Dependencies

### Inbound (Who instantiates/persists entities)

- `SubscriptionService` → creates/updates Subscription, publishes events
- `InvoiceService` → creates/updates Invoice with line items
- `UserService` → creates/updates User, Role, Permission
- `AuthService` → creates RefreshToken on login
- `AuditEventListener` → persists AuditLogEvent after domain events

### Outbound (What entities depend on)

- `Hibernate ORM` — manages entity lifecycle, lazy loading, cascade operations
- `Spring Data JPA` — provides repository interfaces for CRUD
- `PostgreSQL` — validates FK constraints, enforces unique constraints, executes DDL
- `TenantContext` (via Hibernate multitenancy resolver) — routes queries to correct schema

### Configuration

**application.yaml:**
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate      # Do NOT auto-create/drop; Flyway owns schema
      naming:
        physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
      dialect: org.hibernate.dialect.PostgreSQLDialect
      generate_statistics: false    # Disable query stats in prod
    properties:
      hibernate:
        format_sql: true
        jdbc:
          batch_size: 20            # Batch updates for performance
          fetch_size: 50
          batch_versioned_data: true
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

**Entity Auditing:**
```yaml
spring:
  jpa:
    properties:
      hibernate:
        listeners:
          envers:
            autostore: false         # Manual audit control via AuditableEntity
```

---

## Design & Implementation

### Base Entity Hierarchy

All entities inherit from `AuditableEntity`, which extends `BaseEntity`:

```java
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Version
    private Long version;           // Optimistic locking
}

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity extends BaseEntity {
    @CreatedDate
    private Instant createdAt;
    
    @LastModifiedDate
    private Instant updatedAt;
    
    @CreatedBy
    private String createdBy;       // Username from SecurityContext
    
    @LastModifiedBy
    private String updatedBy;
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean deleted = false;
    
    @Column(name = "deleted_at")
    private Instant deletedAt;      // Soft-delete timestamp
}
```

**Key design decisions:**

1. **Optimistic Locking** — `@Version` column enables concurrent-safe updates. Updates with stale version fail; caller must retry.
2. **Soft Delete** — `deleted` flag + `@SQLRestriction("deleted = false")` prevents hard deletes and audit loss.
3. **Automatic Audit** — `@CreatedBy/@LastModifiedBy` stamped by `AuditingEntityListener` reading `SecurityContext`.
4. **IDENTITY strategy** — IDs auto-generated by PostgreSQL sequence; enables batch inserts without waiting for ID.


### Platform Schema Entities

#### Tenant

```java
@Entity
@Table(name = "tenants", schema = "public")
@SQLDelete(sql = "UPDATE public.tenants SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Tenant extends AuditableEntity {
    
    @Column(nullable = false)
    private String name;                    // Tenant business name
    
    @Column(name = "schema_name", nullable = false, unique = true)
    private String schemaName;              // PostgreSQL schema name (e.g., "s_456")
    
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    @Builder.Default
    private Plan planType = Plan.FREE;      // FREE, STARTER, PROFESSIONAL, ENTERPRISE
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;  // ACTIVE, INACTIVE, SUSPENDED, PENDING_ONBOARDING
    
    @Column(name = "owner_email", length = 255)
    private String ownerEmail;              // Tenant admin email
    
    @Column(name = "slug", length = 63, unique = true)
    private String slug;                    // URL-friendly identifier (e.g., "acme-Inc")
    
    @Column(name = "onboarding_step", nullable = false)
    @Builder.Default
    private Integer onboardingStep = 0;     // 0-3: tracks signup progress
}
```

**Critical fields:**

- `schemaName` (UNIQUE) — **Must exist in PostgreSQL before Subscription/Invoice queries**. Schema-per-tenant provisioning ensures this via `TenantFlywayMigrationService.createSchemaAndMigrate()`.
- `status` — Guards billing operations. Cannot stripe bill a SUSPENDED tenant.
- `slug` — **Cached in Redis** for quick resolution. Indexed for login lookups (user enters slug to pick tenant).


#### Plan

```java
@Entity
@Table(name = "plans", schema = "public")
public class Plan extends AuditableEntity {
    
    @Column(nullable = false, unique = true)
    private String name;                    // e.g., "FREE", "STARTER"
    
    @Column(name = "display_name")
    private String displayName;             // e.g., "Free Tier"
    
    private String description;
    
    @Column(name = "price_monthly", precision = 10, scale = 2)
    private BigDecimal priceMonthly;        // INR currency
    
    @Column(name = "price_annual", precision = 10, scale = 2)
    private BigDecimal priceAnnual;         // Discounted annual pricing
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Currency currency = Currency.INR;
    
    @Column(name = "trial_days")
    private Integer trialDays;              // e.g., 0 (FREE has no trial)
    
    @Column(name = "max_users")
    private Integer maxUsers;               // e.g., 5 for FREE tier
    
    @Column(name = "max_api_calls_per_month")
    private Long maxApiCallsPerMonth;       // e.g., 10000, or -1 for unlimited
    
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;        // Soft-availability flag
}
```

**Design pattern — Enum-like but in database:**

Plans are seeded at startup via Flyway migration (V5__seed_plans.sql) and cached in Redis for 1 hour. Changing plan pricing requires database migration + cache flush. Frontend never creates plans; only admins.


#### Permission

```java
@Entity
@Table(name = "permissions", schema = "public")
public class Permission extends AuditableEntity {
    
    @Column(nullable = false, unique = true)
    private String name;                    // e.g., "TENANT_VIEW", "USER_MANAGE"
    
    private String description;             // e.g., "Can view tenant details"
}
```

**Seeded permissions (10 total):**

```
TENANT_VIEW, TENANT_MANAGE,
USER_VIEW, USER_MANAGE, USER_DELETE,
ROLE_VIEW, ROLE_MANAGE,
BILLING_MANAGE,
CUSTOMER_MANAGE,
PRODUCT_MANAGE
```

Accessed by `PermissionController.getAllPermissions()` (public endpoint, used by frontend role-builder UI).


#### PlatformAdmin

```java
@Entity
@Table(name = "platform_admins", schema = "public")
public class PlatformAdmin extends AuditableEntity {
    
    @Column(nullable = false, unique = true)
    private String email;
    
    @Column(name = "password_hash")
    private String passwordHash;            // BCrypt hashed
    
    @Column(name = "first_name")
    private String firstName;
    
    @Column(name = "last_name")
    private String lastName;
    
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;
}
```

**Authentication:** Separate from tenant users. Logs in via `/api/admin/login`, receives JWT with `role="SUPER_ADMIN"`. No TenantContext set (operates across all schemas).


#### AuditLog

```java
@Entity
@Table(name = "audit_logs", schema = "public")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog extends AuditableEntity {
    
    @Column(name = "tenant_id")
    private Long tenantId;
    
    @Column(name = "user_id")
    private String userId;          // UUID or platform admin ID
    
    @Enumerated(EnumType.STRING)
    private AuditAction action;     // CREATE, UPDATE, DELETE, LOGIN, LOGOUT
    
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type")
    private AuditEntityType entityType;  // USER, ROLE, SUBSCRIPTION, INVOICE
    
    @Column(name = "entity_id")
    private String entityId;        // What was changed
    
    @Column(columnDefinition = "JSONB")
    private String changes;         // JSONB diff: {before: {...}, after: {...}}
    
    @Column(name = "ip_address")
    private String ipAddress;
    
    private String description;
    
    @Column(name = "timestamp")
    private Instant timestamp;
}
```

**Immutable once created** (no updates). Written by `AuditEventListener` when `@DomainEvent` is published. Searchable by admin dashboard for compliance.


---

### Tenant Schema Entities

#### User

```java
@Entity
@Table(name = "users")
@SQLDelete(sql = "UPDATE users SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class User extends AuditableEntity {
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false, unique = true)
    private String email;                   // Unique per tenant (not global)
    
    @Column(nullable = false)
    private String password;                // BCrypt hashed
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;                      // FK to Role (tenant-scoped)
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;  // ACTIVE, LOCKED, PENDING_VERIFICATION
    
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private Long tokenVersion = 1L;         // Incremented on password reset (JWT invalidation)
    
    @Column(name = "is_first_login", nullable = false)
    @Builder.Default
    private Boolean isFirstLogin = true;    // Used to enforce password change
}
```

**Key behaviors:**

- **Email uniqueness:** Scoped to tenant schema (multiple tenants can have same email).
- **Token version:** Incremented when password resets; existing JWTs invalidated immediately (checked in `JwtTokenProvider`).
- **Lazy loading of role:** Prevents N+1 queries when loading user list; `@EntityGraph` used in repository for explicit fetch.


#### Role

```java
@Entity
@Table(name = "roles")
@SQLDelete(sql = "UPDATE roles SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Role extends AuditableEntity {
    
    @Column(nullable = false, unique = true)
    private String name;                    // e.g., "OWNER", "ADMIN", "EMPLOYEE"
    
    @ManyToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinTable(
        name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new HashSet<>();
}
```

**System roles (immutable):**

- `OWNER` — All permissions (cannot be deleted/modified)
- `ADMIN` — All except account management
- `EMPLOYEE` — View + create business data only

These are seeded by Flyway during tenant schema initialization (V1__create_roles.sql).

---

#### Subscription

```java
@Entity
@Table(name = "subscriptions")
@SQLDelete(sql = "UPDATE subscriptions SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Subscription extends AuditableEntity {
    
    @Column(name = "plan_id", nullable = false)
    private Long planId;                    // FK to public.plans (current active plan)
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.TRIALING;
    // TRIALING, ACTIVE, PAST_DUE, CANCELLED, EXPIRED
    
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 50)
    @Builder.Default
    private BillingCycle billingCycle = BillingCycle.MONTHLY;  // MONTHLY, ANNUAL
    
    // ── Trial ─────────────────────────────────────────────────────────────────
    @Column(name = "trial_start")
    private Instant trialStart;
    
    @Column(name = "trial_end")
    private Instant trialEnd;
    
    // ── Billing period ────────────────────────────────────────────────────────
    @Column(name = "current_period_start")
    private Instant currentPeriodStart;
    
    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;
    
    // ── Cancellation ──────────────────────────────────────────────────────────
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    
    @Column(name = "cancel_at_period_end")
    @Builder.Default
    private Boolean cancelAtPeriodEnd = false;
    
    // ── Pending upgrade state (in-flight payment) ─────────────────────────────
    /**
     * CRITICAL: Pending upgrade fields prevent premature plan activation.
     * Step 1: initiateUpgrade() sets these fields
     * Step 2: activateUpgradeAfterPayment() clears and changes planId
     * If payment fails: voidPendingUpgrade() clears fields
     */
    @Column(name = "upgrade_pending_invoice_id")
    private Long upgradePendingInvoiceId;   // ID of open invoice awaiting payment
    
    @Column(name = "upgrade_pending_plan_id")
    private Long upgradePendingPlanId;      // Target plan (not active yet)
    
    @Column(name = "upgrade_pending_razorpay_order_id", length = 64)
    private String upgradePendingRazorpayOrderId;  // Allows re-open modal
    
    // ── Scheduled changes (take effect at period end) ──────────────────────────
    /**
     * Billing cycle to switch to at the next period renewal.
     * Set by POST /api/subscriptions/cycle for ANNUAL→MONTHLY transitions.
     * NULL if no cycle change is scheduled.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scheduled_billing_cycle", length = 20)
    private BillingCycle scheduledBillingCycle;
    
    /**
     * Plan ID to switch to at period end (typically FREE plan).
     * Set by POST /api/subscriptions/downgrade/free with atPeriodEnd=true.
     * NULL if no downgrade is scheduled.
     */
    @Column(name = "scheduled_downgrade_plan_id")
    private Long scheduledDowngradePlanId;
    
    /**
     * Optional reason for the scheduled downgrade (e.g., "Too expensive", "Feature mismatch").
     * Stored for audit trail and included in notifications.
     */
    @Column(name = "downgrade_reason", length = 500)
    private String downgradeReason;
    
    // ── Convenience methods ───────────────────────────────────────────────────
    public boolean hasUpgradePending() {
        return upgradePendingInvoiceId != null;
    }
    
    public boolean hasScheduledDowngrade() {
        return scheduledDowngradePlanId != null;
    }
    
    public boolean hasScheduledCycleChange() {
        return scheduledBillingCycle != null && scheduledBillingCycle != billingCycle;
    }
    
    public void clearPendingUpgrade() {
        this.upgradePendingInvoiceId = null;
        this.upgradePendingPlanId = null;
        this.upgradePendingRazorpayOrderId = null;
    }
}
```

**State machine:**

```
TRIALING → [ACTIVE] ← activate()
           ↓
        [PAST_DUE] ← BillingCycleJob fires, invoice unpaid
           ↓
        [CANCELLED] ← cancel() or PAST_DUE for >N days
           ↓
        [EXPIRED] ← cancelAtPeriodEnd + period end reached
```

**Scheduled changes:**

- `scheduledBillingCycle` — Queued cycle change; applied at period end (no payment required for ANNUAL→MONTHLY)
- `scheduledDowngradePlanId` — Queued plan downgrade; applied at period end (customer can cancel before it takes effect)
- Convenience methods enable clean condition checks: `if (sub.hasScheduledDowngrade()) { ... }`

See [subscription-lifecycle.md](../05-platform-billing/subscription-lifecycle.md) for complete state transitions.


#### Invoice

```java
@Entity
@Table(name = "invoices")
@SQLDelete(sql = "UPDATE invoices SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Invoice extends AuditableEntity {
    
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;            // FK to Subscription
    
    @Column(name = "invoice_number", nullable = false, unique = true, length = 50)
    private String invoiceNumber;           // e.g., "INV-2026-0001" (auto-generated)
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.DRAFT;
    // DRAFT → OPEN (finalized) → PAID/VOID
    
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;  // Sum of line items (no tax)
    
    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;  // Total tax across line items
    
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;  // Discounts applied
    
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;  // subtotal + tax - discount
    
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";       // Currency (always INR for now)
    
    @Column(name = "due_date")
    private Instant dueDate;                // e.g., 15 days from issue date
    
    @Column(name = "paid_at")
    private Instant paidAt;                 // When payment was received
    
    @Column(name = "razorpay_invoice_id")
    private String razorpayInvoiceId;       // FK to Razorpay invoice object
    
    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;                  // Pre-signed S3 URL (if generated)
    
    @Column(name = "billing_period_start")
    private Instant billingPeriodStart;     // Period this invoice covers
    
    @Column(name = "billing_period_end")
    private Instant billingPeriodEnd;       // Period this invoice covers
    
    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InvoiceLineItem> lineItems = new ArrayList<>();
}
```

**Key changes:**

- `subscriptionId` — Now tracks which subscription this invoice relates to
- `mappedBy = "invoice"` — Relationship is managed from InvoiceLineItem side (unidirectional from Invoice perspective)
- Explicit total fields (`subtotal`, `taxAmount`, `discountAmount`, `totalAmount`) — Immutable once OPEN
- `paid_at` — Timestamp when payment received (set by PaymentService)


#### InvoiceLineItem

```java
@Entity
@Table(name = "invoice_line_items")
@SQLDelete(sql = "UPDATE invoice_line_items SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class InvoiceLineItem extends AuditableEntity {
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;                // FK to Invoice (manages the relationship)
    
    @Column(nullable = false, length = 500)
    private String description;             // e.g., "Professional Plan - Monthly"
    
    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ONE;
    
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;
    
    @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalPrice = BigDecimal.ZERO;  // quantity × unitPrice (or override)
    
    @Enumerated(EnumType.STRING)
    @Column(name = "line_item_type", nullable = false, length = 50)
    private LineItemType lineItemType;      // PLAN, ADDON, DISCOUNT, TAX
}
```

**Design choice — `mappedBy` from Invoice side:**

The relationship is now:
- **Invoice** → `@OneToMany(mappedBy = "invoice")` — Declares relationship
- **InvoiceLineItem** → `@ManyToOne @JoinColumn` — Manages the foreign key

This prevents issues with `orphanRemoval` and allows line items to be created independently before being added to an invoice.

**Important:** When clearing line items:
```java
// WRONG: Direct assignment
invoice.setLineItems(newList);  // orphanRemoval may not trigger

// CORRECT: Manual clear + add
invoice.getLineItems().clear();
invoice.getLineItems().addAll(newList);
```


#### Payment

```java
@Entity
@Table(name = "payments")
@SQLDelete(sql = "UPDATE payments SET deleted = true, deleted_at = NOW() WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
public class Payment extends AuditableEntity {
    
    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;                 // FK to Invoice
    
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;              // INR
    
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;
    // PENDING → SUCCEEDED → FAILED (and retry)
    
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 50)
    private PaymentMethod paymentMethod;    // RAZORPAY, BANK_TRANSFER, MANUAL
    
    // ── Razorpay fields ───────────────────────────────────────────────────────
    @Column(name = "razorpay_order_id")
    private String razorpayOrderId;         // Razorpay order object
    
    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;       // Razorpay payment object (after payment)
    
    @Column(name = "razorpay_signature")
    private String razorpaySignature;       // HMAC-SHA256 signature (for verification)
    
    // ── Idempotency & Retry ───────────────────────────────────────────────────
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;          // Format: "pay-{invoiceId}-{uuid}"
    
    @Column(name = "failure_code", length = 100)
    private String failureCode;             // e.g., "INVALID_SIGNATURE", "NETWORK_ERROR"
    
    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;          // Reason for FAILED status
    
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;
    
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;            // When to attempt next retry
    
    @Column(name = "paid_at")
    private Instant paidAt;                 // When payment was successfully captured
}
```

**Key changes:**

- `idempotency_key` — Now **unique constraint** to prevent duplicate payment processing
- `failure_code` + `failure_message` — Split for better error categorization and filtering
- `nextRetryAt` — Replaces `lastRetryAt`; enables exponential backoff scheduling (1s, 2s, 4s, 8s...)
- `paidAt` — Timestamp when payment successfully captured (matches Invoice.paidAt)
- `currency` — Explicit currency field (always INR for now)

**Payment flow:**

1. POST `/api/payments/process/{invoiceId}` creates Payment (status=PENDING), calls Razorpay to get order ID
2. Frontend opens Razorpay modal, user completes payment
3. Razorpay webhook or manual verify POST `/api/payments/verify` validates HMAC signature and captures funds
4. Payment status changed to SUCCEEDED, Invoice marked PAID

See [payment-processing.md](../05-platform-billing/payment-processing.md) for complete flow.


#### UsageRecord & UsageSummary

```java
@Entity
@Table(name = "usage_records")
public class UsageRecord extends AuditableEntity {
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageMetric metric;             // API_CALLS, API_GB, INVOICES_CREATED, USERS
    
    @Column(name = "quantity", nullable = false)
    private Long quantity;
    
    @Column(name = "metric_period_start")
    private Instant metricPeriodStart;      // e.g., 2026-04-01 (month start)
    
    @Column(name = "metric_period_end")
    private Instant metricPeriodEnd;        // e.g., 2026-04-30 (month end)
    
    @Column(columnDefinition = "JSONB")
    private String metadata;                // Custom attributes (e.g., customer_id, product_id)
}

@Entity
@Table(name = "usage_summaries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageSummary extends AuditableEntity {
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageMetric metric;
    
    @Column(nullable = false)
    private Long totalQuantity;             // Aggregated across month
    
    @Column(name = "billing_period_start")
    private Instant billingPeriodStart;
    
    @Column(name = "billing_period_end")
    private Instant billingPeriodEnd;
    
    @Column(name = "overage_quantity")
    @Builder.Default
    private Long overageQuantity = 0L;      // If over plan limit
    
    @Column(name = "overage_charge", precision = 10, scale = 2)
    private BigDecimal overageCharge;       // Cost for overage (if applicable)
}
```

**Aggregation pattern:**

- `UsageRecord` captures raw events (API call made, file uploaded).
- `UsageAggregationJob` (Quartz, hourly) rolls up records into `UsageSummary` by metric + billing period.
- `BillingCycleJob` checks `UsageSummary` vs plan limits; creates overage line items if needed.


---

### Business Domain Entities

#### Customer

```java
@Entity
@Table(name = "customers")
@SQLDelete(sql = "UPDATE customers SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
public class Customer extends AuditableEntity {
    
    @Column(nullable = false, length = 255)
    private String name;
    
    @Column(length = 255)
    private String email;
    
    @Column(length = 50)
    private String phone;
    
    @Column(columnDefinition = "TEXT")
    private String address;                 // Free-form address (street, city, state, zip)
    
    /**
     * GST Identification Number — 15-character alphanumeric.
     * Null for B2C customers or non-GST-registered businesses.
     * Printed on GST invoices when present.
     */
    @Column(name = "gstin", length = 20)
    private String gstin;
    
    /**
     * Razorpay customer ID (cust_XXXX).
     * Created via PaymentGatewayPort.createCustomer() during customer creation.
     * Null until the customer has been synced to Razorpay.
     */
    @Column(name = "razorpay_customer_id", length = 100)
    private String razorpayCustomerId;
}
```

**Key fields:**

- `gstin` — GST Identification Number for B2B invoices in India (optional for B2C)
- `razorpayCustomerId` — Created on first payment link generation; enables Razorpay to track customer across invoices
- Uses auto-generated `id` (not custom `customerId`); IDs are tenant-scoped and not visible to end customers


#### Product

```java
@Entity
@Table(name = "products")
@SQLDelete(sql = "UPDATE products SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
public class Product extends AuditableEntity {
    
    @Column(nullable = false, length = 255)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    /**
     * Base price per unit in the tenant's currency.
     * Snapshotted onto invoice line items at creation time.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;
    
    /**
     * Default GST / tax rate for this product (e.g. 18.00 = 18%).
     * Snapshotted onto invoice line items at creation time.
     * Zero = tax-exempt product.
     */
    @Column(name = "tax_percentage", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxPercentage = BigDecimal.ZERO;
    
    /**
     * HSN (goods) or SAC (services) code for GST compliance.
     * Printed on GST invoices. Null for non-Indian tenants.
     */
    @Column(name = "hsn_sac_code", length = 20)
    private String hsnSacCode;
    
    /**
     * Unit of measure printed on invoice: "hrs", "kg", "units", "license", etc.
     * Null = no unit label printed.
     */
    @Column(length = 50)
    private String unit;
    
    /**
     * False = deactivated. Deactivated products cannot be added to new invoices.
     * Use deactivation instead of deletion when a product is discontinued.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
```

**Design pattern — Snapshotting:**

When a product is added to a BusinessInvoice, price and tax_percentage are **snapshotted** onto the line item. Future product changes do NOT affect historical invoices (legal/audit requirement).

```
Product price changes → stored on BusinessInvoiceItem at creation time
Product deactivated → new invoices cannot use it, but old invoices retain price history
```


#### BusinessInvoice

```java
@Entity
@Table(name = "business_invoices")
@SQLDelete(sql = "UPDATE business_invoices SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
public class BusinessInvoice extends AuditableEntity {
    
    /**
     * A manual invoice raised by the tenant for their customer.
     *
     * Lifecycle:
     *   DRAFT  → line items can be added/removed, totals recalculated
     *   OPEN   → finalized, due date set, PDF generated, sent to customer
     *   PAID   → one or more BusinessPayments have covered the total_amount
     *   VOID   → cancelled before payment (cannot void a PAID invoice)
     *
     * Intentionally SEPARATE from the platform Invoice entity (which records
     * what the tenant owes the platform for their subscription).
     *
     * invoiceNumber format: BINV-{tenantId}-{YYYYMM}-{seq}
     * "B" prefix distinguishes from platform INV-* numbers.
     *
     * Totals are stored explicitly (not computed on read) for audit immutability.
     * Recalculation happens only in BusinessInvoiceService when line items change,
     * and only while the invoice is in DRAFT status.
     */
    
    @Column(name = "invoice_number", nullable = false, unique = true, length = 50)
    private String invoiceNumber;
    
    /**
     * FK to customers(id) — within this tenant schema.
     * Stored as a plain Long (not a @ManyToOne) to avoid cross-entity lazy
     * loading issues. Customer details are fetched explicitly when needed.
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.DRAFT;
    
    // ── Financials ────────────────────────────────────────────────────────────
    
    /** Sum of (unit_price × quantity) across all line items. Excludes tax. */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;
    
    /** Sum of per-line-item tax amounts. */
    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;
    
    /** subtotal + taxAmount. The final amount the customer owes. */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;
    
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";
    
    // ── Optional fields ───────────────────────────────────────────────────────
    
    /** Free-text note printed at the bottom of the PDF invoice. */
    @Column(columnDefinition = "TEXT")
    private String notes;
    
    /** Set when the invoice is finalized. +N days from finalization date. */
    @Column(name = "due_date")
    private Instant dueDate;
    
    @Column(name = "issued_date")
    private Instant issuedDate;
    
    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;                  // Pre-signed S3 URL (if generated)
    
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private List<BusinessInvoiceItem> items = new ArrayList<>();
}
```

**Differences from platform Invoice:**

- **References Customer (business customer)**, not a Subscription
- **Manual creation** by tenant user via web form (not auto-generated from subscription billing)
- **Explicit totals** stored for audit immutability; recalculated only in DRAFT status
- **Separate from platform billing** — what tenant owes platform (Invoice) is independent from what customer owes tenant (BusinessInvoice)


---

## Flow Diagram

### Entity Lifecycle: Subscription Upgrade

```
User: "Upgrade to Professional"
  ↓
SubscriptionService.initiateUpgrade()
  ├─ Create Invoice(status=DRAFT) with PlanDifference line item
  ├─ Call Razorpay.createOrder(amount)
  └─ Subscription: set upgradePendingInvoiceId, upgradePendingPlanId, upgradePendingRazorpayOrderId
       ↓ (Invoice automatically finalized to OPEN by InvoiceService)
       ↓ (Frontend: show Razorpay modal with order ID)
       ↓
Frontend: User clicks "Pay" → Razorpay processes payment
       ↓
Razorpay: calls webhook /api/webhooks/razorpay → RazorpayWebhookController
       ↓
PaymentService.verifyAndCapturePayment()
  ├─ Validate HMAC signature
  ├─ Create Payment(status=SUCCEEDED) in DB
  ├─ Call Razorpay.capturePayment()
  ├─ Call SubscriptionService.activateUpgradeAfterPayment()
  │   └─ Subscription: set planId = upgradePendingPlanId, clear pending fields
  └─ Invoice: set status=PAID, amountPaid=amountDue
```

**If upgrade fails:**
```
  ├─ Payment(status=FAILED)
  ├─ Subscription: clear pending fields (no auto-rollback, manual)
  └─ Frontend: show error, user can retry
```


## Code References

| Component | Tag | Location | Purpose |
|-----------|-----|----------|---------|
| AuditableEntity | [SHD-2] | [src/main/java/com/mtbs/shared/entity/AuditableEntity.java](../../src/main/java/com/mtbs/shared/entity/AuditableEntity.java) | Base class with audit fields |
| Tenant | [TEN-1] | [src/main/java/com/mtbs/tenant/entity/Tenant.java](../../src/main/java/com/mtbs/tenant/entity/Tenant.java) | Platform tenant record |
| Plan | [TEN-2] | [src/main/java/com/mtbs/tenant/entity/Plan.java](../../src/main/java/com/mtbs/tenant/entity/Plan.java) | Subscription plan template |
| User | [AUTH-1] | [src/main/java/com/mtbs/auth/entity/User.java](../../src/main/java/com/mtbs/auth/entity/User.java) | Tenant user (in tenant schema) |
| Role | [AUTH-2] | [src/main/java/com/mtbs/auth/entity/Role.java](../../src/main/java/com/mtbs/auth/entity/Role.java) | User role for RBAC |
| Permission | [AUTH-3] | [src/main/java/com/mtbs/auth/entity/Permission.java](../../src/main/java/com/mtbs/auth/entity/Permission.java) | Platform-level permission |
| RolePermission | [AUTH-4] | [src/main/java/com/mtbs/auth/entity/RolePermission.java](../../src/main/java/com/mtbs/auth/entity/RolePermission.java) | Role-Permission join |
| RefreshToken | [AUTH-5] | [src/main/java/com/mtbs/auth/entity/RefreshToken.java](../../src/main/java/com/mtbs/auth/entity/RefreshToken.java) | JWT refresh token |
| Subscription | [BIL-1] | [src/main/java/com/mtbs/billing/entity/Subscription.java](../../src/main/java/com/mtbs/billing/entity/Subscription.java) | Plan subscription record |
| Invoice | [BIL-2] | [src/main/java/com/mtbs/billing/entity/Invoice.java](../../src/main/java/com/mtbs/billing/entity/Invoice.java) | Billing invoice |
| InvoiceLineItem | [BIL-3] | [src/main/java/com/mtbs/billing/entity/InvoiceLineItem.java](../../src/main/java/com/mtbs/billing/entity/InvoiceLineItem.java) | Invoice line item |
| Payment | [BIL-4] | [src/main/java/com/mtbs/billing/entity/Payment.java](../../src/main/java/com/mtbs/billing/entity/Payment.java) | Payment record (Razorpay) |
| UsageRecord | [BIL-5] | [src/main/java/com/mtbs/billing/entity/UsageRecord.java](../../src/main/java/com/mtbs/billing/entity/UsageRecord.java) | Raw usage event |
| UsageSummary | [BIL-6] | [src/main/java/com/mtbs/billing/entity/UsageSummary.java](../../src/main/java/com/mtbs/billing/entity/UsageSummary.java) | Monthly usage aggregation |
| Customer | [BUS-1] | [src/main/java/com/mtbs/business/customer/entity/Customer.java](../../src/main/java/com/mtbs/business/customer/entity/Customer.java) | Business customer record |
| Product | [BUS-8] | [src/main/java/com/mtbs/business/product/entity/Product.java](../../src/main/java/com/mtbs/business/product/entity/Product.java) | Product offered by tenant |
| BusinessInvoice | [BUS-15] | [src/main/java/com/mtbs/business/invoice/entity/BusinessInvoice.java](../../src/main/java/com/mtbs/business/invoice/entity/BusinessInvoice.java) | Tenant's invoice to customer |
| AuditLog | [ADM-5] | [src/main/java/com/mtbs/admin/service/AuditLogService.java](../../src/main/java/com/mtbs/admin/service/AuditLogService.java) | Audit trail (immutable) |


## Rules & Constraints

1. **Soft delete is mandatory** — All entities extend AuditableEntity with `@SQLRestriction`. Hard delete (DROP) is NOT ALLOWED. Deleted records remain for audit compliance. When querying, Hibernate automatically filters `deleted=false`.

2. **Version field enables optimistic locking** — Concurrent updates to same entity row will fail if version is stale. Caller must retry after fresh load. Do NOT increment version manually; Hibernate does this automatically on flush.

3. **TenantContext must be set BEFORE accessing tenant schema entities** — Queries to User, Role, Subscription, etc. will hit wrong schema or fail if TenantContext not set. Always set in filter chain or explicit caller responsibility (service methods must document).

4. **Cascade orphanRemoval must be used carefully** — Invoice.lineItems uses `CascadeType.ALL + orphanRemoval=true`. Never do `invoice.setLineItems(newList)` directly; use `list.clear()` + `list.addAll()` instead.

5. **FK constraints are enforced by PostgreSQL** — Inserting Invoice with invalid planId will fail. Tests use `TestSchemaHelper` to seed Plan records before creating Subscription/Invoice.

6. **Unique constraints apply per tenant for tenant entities** — User.email is unique WITHIN a tenant schema, not globally. Multiple tenants can have same email.

7. **JSONB columns are flexible but NOT queryable** — Address, metadata fields use JSONB for schema-less attributes. Cannot do JPA predicates on nested JSONB; use native SQL queries if filtering needed.


## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|----------------| ------------|----------|
| Insert User with duplicate email in tenant | `DataIntegrityViolationException` (unique constraint) | 409 Conflict | Retry with different email; check existing users |
| Update Invoice with stale version (concurrent edit) | `OptimisticLockingFailureException` | 409 Conflict | Reload Invoice from DB; retry update with fresh version |
| Delete Plan that has active subscriptions | `DataIntegrityViolationException` (FK constraint) | 400 Bad Request | Mark Plan inactive instead of deleting; prevent new signups but keep existing subscriptions |
| Query Subscription without TenantContext set | `ConstraintViolationException` or silent wrong-schema hit | 500 Internal Server Error | Always set TenantContext in filter chain before business logic |
| Create Payment with negative amount | `ConstraintViolationException` (check constraint) or validation error | 400 Bad Request | Validate amount > 0 in service before persistence |
| Fetch User from public schema (wrong schema) | `EntityNotFoundException` or wrong result | 404 Not Found | Ensure TenantContext set before User queries |


## Edge Cases & Special Handling

### Concurrency: Role Permission Update

**Scenario:** Admin assigns permission to role while user's permission cache is still hot.

```
User A: Has cached permissions = [USER_VIEW]
  ↓
Admin: Adds USER_MANAGE to User A's role
  ↓
PermissionCacheService.evictUser(schemaName, userId)
  └─ Clears Redis key "perms:{schema}:{userId}"
  ↓
User A: Next API request → permission cache MISS → re-fetches from DB
  └─ Now has [USER_VIEW, USER_MANAGE]
```

**Pattern:** Always invalidate cache immediately after role permission changes via `@Transactional` commit listener.


### Timezone Handling

**Design:** All Instant fields are UTC (timestamp). Stored as `BIGINT` (milliseconds since epoch) in PostgreSQL. Frontend converts to user's local timezone.

```java
@Column(name = "billing_period_start")
private Instant billingPeriodStart;   // Always UTC, e.g., 2026-04-01T00:00:00Z
```

**Calculation error:** DO NOT use `LocalDate.now()` + convert to Instant. Use `Instant.now()` directly.


### Tenant Isolation: Cross-Schema FK Validity

**Risk:** Invoice.planId (in tenant schema) points to public.plans. Both have same table design but different schemas.

```
Invoice (tenant schema s_456) → planId=3 (FK to public.plans)
Payment (tenant schema s_456) → invoi_id=1 (FK to Subscription in same schema)
```

**Guarantee:** PostgreSQL enforces FK at insert time. Cannot insert Invoice with invalid planId (fails immediately with constraint error).

**Testing:** Each test creates Plan in public schema AND separate tenant schema before creating Subscription.


### Empty State: New Tenant First Login

**Scenario:** Tenant signs up. No users, roles, subscriptions yet (onboarding incomplete).

**Behavior:**

```
GET /api/users → 200 OK with empty list []
GET /api/subscriptions → 204 No Content (or 200 with [])
GET /api/invoices → 204 No Content
```

**Important:** DO NOT error on empty results. Explicitly handle and return empty collections.


---

## Known Issues & Limitations

### 1. N+1 Query Problem Without EntityGraph

**Issue:** Fetching 100 users WITH their roles issue 101 queries (1 for users, 100 for roles) instead of 1 JOIN.

**Workaround:**
```java
@EntityGraph(attributePaths = {"role"})
Page<User> findByDeletedFalse(Pageable pageable);  // Added to UserRepository
```

**Production impact:** Slow dashboards, high DB CPU. Monitor slow query logs; add @EntityGraph wherever needed.


### 2. Soft Delete Gotcha: Archived Records

**Issue:** Deleted records remain queryable if developer forgets `@SQLRestriction`.

**Mitigation:** All entities extend AuditableEntity which has `@SQLRestriction("deleted = false")`. Cannot be overridden accidentally.


### 3. JSONB Query Limitation

**Issue:** `address` JSONB column cannot be filtered in JPQL:
```java
// INVALID: Hibernate cannot translate JSONB path to SQL
List<Customer> findByAddressCity(String city);

// VALID: Use native SQL
@Query(value = "SELECT * FROM customers WHERE address->>'city' = ?1", nativeQuery = true)
List<Customer> findByAddressCity(String city);
```

**Production impact:** Custom reporting queries must use native SQL for JSONB filtering.


### 4. Cascade Behavior Complexity

**Issue:** `CascadeType.REMOVE` on oneToMany can delete child records unintentionally.

**Current approach:** Uses `orphanRemoval = true` (safer than REMOVE). Deleting line items explicitly removes them; setting null orphans them (optional removable).

**Future improvement:** Audit orphaned records for compliance.


## Future Improvements

1. **Entity Versioning** — Track historical values of key fields (e.g., Subscription.planId changes). Currently audit_log stores JSON diffs; could use Envers for auto-versioning.

2. **Lazy-Load Instrumentation** — Add `@LazyCollection(LazyCollectionOption.EXTRA)` on large collections to count without loading all records.

3. **Partitioned Tables** — Invoice table will grow large. Partition by billing_period_start for faster queries on recent invoices.

4. **Denormalized View Tables** — Create read-only views for reports (e.g., monthly_revenue_summary) instead of computing aggregates on-the-fly.

5. **Time-Series Tables** — Move UsageRecord to time-series database (TimescaleDB or ClickHouse) for better query performance on high-volume usage data.


## Related Documents

- [system-design.md](../01-architecture/system-design.md) — Module architecture and entity ownership
- [multi-tenancy-strategy.md](../02-multi-tenancy/multi-tenancy-strategy.md) — Schema-per-tenant design rationale
- [subscription-lifecycle.md](../05-platform-billing/subscription-lifecycle.md) — Subscription entity state machine
- [invoice.md](../05-platform-billing/invoice.md) — Invoice entity complete lifecycle
- [payment-processing.md](../05-platform-billing/payment-processing.md) — Payment entity with Razorpay integration
- [error-handling.md](../06-api/error-handling.md) — Exception classes related to entity operations
