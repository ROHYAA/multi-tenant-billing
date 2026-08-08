---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - billing
  - subscription
  - platform-billing
  - lifecycle
  - upgrade
  - downgrade
  - trial
  - proration
  - plan-change
related_documents:
  - ./invoice.md
  - ./payment-processing.md
  - ../07-data-model/entities.md
---

# Subscription Lifecycle

## Executive Summary

A **subscription** represents a tenant's recurring commitment to a billing plan (Free, Pro, Enterprise). It progresses through states (TRIALING → ACTIVE → CANCELLED/EXPIRED) and supports mid-cycle plan changes (upgrades, downgrades, billing cycle swaps). MTBS uses a **2-step payment model for upgrades**: Step 1 creates an invoice and Razorpay order (no immediate state change), Step 2 verifies payment and activates the new plan. Without proper subscription lifecycle management, billing would be chaotic—tenants could upgrade but never pay, downgrades could cause refund disputes, and trial expirations would not trigger auto-cancellations. This document explains the state machine, the critical 2-step upgrade flow, proration logic, and scheduled changes.

---

## Context / Problem

### Why a Complex Subscription Lifecycle?

SaaS subscriptions are not simple purchases. Consider the scenarios:

| Scenario | Complexity | Impact |
|----------|-----------|--------|
| Tenant on trial expires | Need auto-cancel or auto-downgrade | Revenue loss if not automatic |
| Tenant upgrades mid-month | Need proration (credit old plan, charge new plan) | Disputes if proration broken |
| Tenant pays upgrade but then refunds | Need to downgrade back after days | Chargeback if subscription not reversed |
| Tenant upgrades but abandons checkout | Need to clear pending upgrade state | Stuck state if not cleaned up |
| Tenant downgrades but changes mind | Need to cancel any scheduled downgrade | Lost upgrade if not handled |
| Tenant changes billing cycle on annual plan | Need to charge for cycle change at period end | Revenue recognition issues |

Each scenario requires careful state transitions and compensating actions.

### Why 2-Step Upgrade Instead of 1-Step?

**Problem with 1-step upgrade** (direct approach):
```
POST /api/subscriptions/upgrade/pro [user clicks button]
  ↓ SubscriptionService.upgrade()
  ├─ Change planId immediately
  ├─ Create invoice for difference
  ├─ Call Razorpay.createOrder()
  ├─ Send order to frontend
  ├─ HTTP 200 response
  
[Frontend opens Razorpay checkout]
[User pays]
[User closes browser after payment — frontend never calls callback]
  ↓
Subscription is already UPGRADED, but payment is never captured.
Tenant is at PRO plan, but Razorpay marked it as AUTHORIZED, not CAPTURED.
Revenue never recognized. Chargeback risk.
```

**Solution: 2-step upgrade** (MTBS approach):
```
Step 1: POST /api/subscriptions/upgrade/pro
  ├─ Create OPEN invoice (not yet paid)
  ├─ Set upgradePendingInvoiceId on subscription
  ├─ Set upgradePendingPlanId on subscription
  ├─ planId NOT changed yet (still old plan)
  ├─ Create Razorpay order
  ├─ Return razorpayOrderId + amount
  └─ HTTP 200 (user proceeds to payment)

[Frontend opens Razorpay checkout with Razorpay.openCheckout()]

Step 2: POST /api/payments/verify [backend webhook or frontend callback]
  ├─ Verify Razorpay signature
  ├─ Call SubscriptionService.activateUpgradeAfterPayment(invoiceId)
  ├─ Change planId (now safe, payment confirmed)
  ├─ Mark invoice as PAID
  ├─ Clear upgradePendingInvoiceId
  ├─ Fire PLAN_UPGRADED event
  └─ HTTP 200
```

Guarantee: planId only changes after payment is confirmed. If payment fails, subscription remains unchanged.

### Why Scheduled Downgrade Instead of Immediate Change?

**Downgrade options**:

1. **Immediate downgrade** — Take effect now
   - ✅ Simple implementation
   - ❌ Tenant loses access to PRO features mid-period
   - ❌ No refund path (unjust enrichment claim)

2. **Scheduled downgrade** — Take effect at period end
   - ✅ Tenant can continue using PRO until period expires
   - ✅ No refund needed (charged for full period)
   - ✅ Can cancel the scheduled downgrade if customer changes mind
   - ✅ Better UX: "Your plan downgrades to Free on May 1"

MTBS implements scheduled downgrade by default (recommended), with immediate as an option.

---

## Dependencies

### Inbound (Who Creates/Manages Subscriptions)
- `AuthService` (signup flow) → `SubscriptionService.createAutoSubscriptionToFreePlan()` — New tenant auto-subscribes to Free plan
- `SubscriptionController` (user actions) → upgrade, downgrade, cycle change, cancellation
- `BillingCycleJob` (scheduled) → `SubscriptionService.expireTrials()`, `markExpiredSubscriptions()` — Auto-expire trials/subscriptions
- `PaymentService` → `SubscriptionService.activateUpgradeAfterPayment()` — Activate upgrade after payment verified

### Outbound (What Subscription Calls)
- `InvoiceService` → `generateInvoice()` — Create invoice for upgrade charge
- `PaymentService` → `RazorpayPaymentGateway.createOrder()` — Create Razorpay order for payment
- `PlanService` → `getPlanById()`, `getPlanByName()` — Fetch plan details for pricing
- `ProrationService` → `calculateUpgradeProration()`, `calculateDowngradeRefund()` — Calculate credit/charge
- `OutboxEventPublisher` → `save(event)` — Publish PLAN_UPGRADED, PLAN_DOWNGRADED, SUBSCRIPTION_CANCELLED events
- `TenantBillingDashboardService` → Called after state change to invalidate cache

### Configuration
- `spring.jpa.hibernate.ddl-auto: validate` — Schema changes via Flyway only
- `mtbs.trial-days: 14` — Free plan trial duration
- `mtbs.billing-cycle.default: MONTHLY` — Default billing cycle
- `mt bs.razorpay.key-id`, `mtbs.razorpay.key-secret` — Razorpay integration (for orders)

---

## Design / Implementation

### Subscription Entity

```java
@Entity
@Table(name = "subscriptions")
public class Subscription extends AuditableEntity {
    
    // ── Core (always present) ──
    @Column(name = "plan_id", nullable = false)
    private Long planId;              // Current ACTIVE plan
    
    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status; // TRIALING, ACTIVE, PAST_DUE, CANCELLED, EXPIRED
    
    @Enumerated(EnumType.STRING)
    private BillingCycle billingCycle; // MONTHLY or ANNUAL
    
    // ── Trial (set if status=TRIALING) ──
    private Instant trialStart;
    private Instant trialEnd;          // 14 days after creation by default
    
    // ── Billing period (recurring) ──
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;   // Trial expiry or next billing date
    
    // ── Cancellation ──
    private Instant cancelledAt;       // When user clicked "cancel"
    private Boolean cancelAtPeriodEnd;  // If true, cancel at current period end
    
    // ── Razorpay ──
    private String razorpaySubscriptionId;
    private String razorpayCustomerId;
    
    // ── Pending upgrade (set during step 1, cleared during step 2) ──
    private Long upgradePendingInvoiceId;      // OPEN invoice for payment
    private Long upgradePendingPlanId;         // Target plan for upgrade
    private String upgradePendingRazorpayOrderId; // Razorpay order ID
    
    // ── Scheduled changes (take effect at period end) ──
    private BillingCycle scheduledBillingCycle; // MONTHLY→ANNUAL or vice versa
    private Long scheduledDowngradePlanId;     // Plan to downgrade to at period end
}
```

### Subscription Status States

```
TRIALING
  ├─ Initial state after signup
  ├─ trialStart + trialEnd set
  ├─ currentPeriodEnd = trialEnd
  ├─ Transition: Trial expires (14 days) → Job marks as EXPIRED
  ├─ Transition: User upgrades → ACTIVE (pays for upgrade immediately)
  └─ Transition: User cancels during trial → CANCELLED

ACTIVE
  ├─ Subscription is paid and current
  ├─ User has access to plan features
  ├─ currentPeriodStart, currentPeriodEnd set
  ├─ Next billing at currentPeriodEnd or when manual upgrade happens
  ├─ Transition: BillingCycleJob executes, generates invoice, payment due
  ├─ Transition: Payment fails 3 times → PAST_DUE
  ├─ Transition: User cancels → CANCELLED (takes effect immediately or at period end)
  ├─ Transition: Period expires, no payment → EXPIRED
  └─ Transition: User upgrades → Stays ACTIVE (planId changes, period resets)

PAST_DUE
  ├─ Invoice unpaid for >7 days (configurable)
  ├─ Limited features or read-only access
  ├─ Retry job attempts payment 3 times
  ├─ Transition: Payment succeeds → ACTIVE
  ├─ Transition: Retry exhausted → EXPIRED
  └─ Transition: User manually pays, refresh to ACTIVE

CANCELLED
  ├─ User requested cancellation
  ├─ If cancelAtPeriodEnd=false, access revoked immediately
  ├─ If cancelAtPeriodEnd=true, access continues until currentPeriodEnd
  ├─ No further charges after currentPeriodEnd
  ├─ Transition: Manual reactivation → ACTIVE (creates new invoice if past period)
  └─ No transition beyond this

EXPIRED
  ├─ Trial expired without conversion, OR
  ├─ Paid subscription period ended without payment, OR
  ├─ Cancellation period ended
  ├─ No access to features; read-only data view only
  ├─ Transition: Reactivation with payment → ACTIVE
  └─ No further automatic transitions
```

### Upgrade: The Critical 2-Step Flow

**Step 1: Initiate Upgrade**

`POST /api/subscriptions/upgrade/pro { "billingCycle": "MONTHLY" }`

```java
@Transactional
public SubscriptionOrderResponse initiateUpgradeToPro(UpgradeRequest request) {
    Subscription current = subscriptionRepository
        .findFirstByStatusIn([ACTIVE, TRIALING])
        .orElseThrow();
    
    // Validate: no upgrade already in flight
    if (current.getUpgradePendingInvoiceId() != null) {
        throw new SubscriptionException("Upgrade already in progress");
    }
    
    // Fetch target plan (Pro)
    Plan proPlan = planService.getPlanByName("PRO");
    
    // Calculate proration: credit old plan, charge new plan
    ProrationResult proration = prorationService.calculateUpgradeProration(
        current.getPlanId(),
        proPlan.getId(),
        current.getBillingCycle(),
        request.getBillingCycle()
    );
    
    // Create OPEN invoice for the upgrade charge
    Invoice invoice = invoiceService.generateInvoice(
        subscriptionId,
        current.getPlanId(),
        proPlan.getId(),
        proration.getChargeAmount()
    );
    
    // Create Razorpay order (order object, not subscription)
    RazorpayOrderResponse order = RazorpayPaymentGateway.createOrder(
        amount = invoice.getTotalAmount() * 100, // in paise
        order_id = invoice.getId().toString(),
        receipt = invoice.getInvoiceNumber()
    );
    
    // Mark subscription with pending upgrade
    current.setUpgradePendingInvoiceId(invoice.getId());
    current.setUpgradePendingPlanId(proPlan.getId());
    current.setUpgradePendingRazorpayOrderId(order.getId());
    subscriptionRepository.save(current);
    
    // Publish event for audit
    outboxEventPublisher.save(
        new AuditLogEvent(
            action=PLAN_CHANGE_INITIATED,
            fromPlan=current.getPlanId(),
            toPlan=proPlan.getId()
        )
    );
    
    // Return Razorpay checkout details
    return SubscriptionOrderResponse.builder()
        .razorpayOrderId(order.getId())
        .razorpayKeyId(razorpayKeyId)
        .amount(invoice.getTotalAmount()) // in INR
        .paise(invoice.getTotalAmount() * 100)
        .build();
}
```

Response sent to frontend:
```json
{
  "razorpayOrderId": "order_j5v6k9",
  "razorpayKeyId": "rzp_live_abc123",
  "amount": 1500,
  "paise": 150000
}
```

Frontend opens Razorpay:
```javascript
Razorpay.openCheckout({
    key: "rzp_live_abc123",
    order_id: "order_j5v6k9",
    amount: 150000,
    handler: function(response) {
        // User paid successfully, call backend verify
        POST /api/payments/verify { razorpay_payment_id: response.razorpay_payment_id, ... }
    }
});
```

**Step 2: Activate Upgrade (After Payment Verified)**

`POST /api/payments/verify { ... }` — Called by frontend or webhook

```java
// In PaymentService.handleSubscriptionUpgradePayment(invoiceId):
@Transactional
public void handleSubscriptionUpgradePayment(Long invoiceId) {
    // Verify Razorpay signature (handled by caller)
    
    // Delegate to SubscriptionService
    subscriptionService.activateUpgradeAfterPayment(invoiceId);
}

// In SubscriptionService.activateUpgradeAfterPayment(invoiceId):
@CacheEvict(value = "dashboard", allEntries = true)
@Transactional
public void activateUpgradeAfterPayment(Long invoiceId) {
    // Find subscription linked to this invoice
    Subscription subscription = subscriptionRepository
        .findByUpgradePendingInvoiceId(invoiceId)
        .orElseThrow(() -> ResourceException.notFound("Subscription with pending upgrade"));
    
    // Get target plan
    Plan oldPlan = planService.getPlanById(subscription.getPlanId());
    Plan newPlan = planService.getPlanById(subscription.getUpgradePendingPlanId());
    
    // Update subscription: swap plan and reset billing period
    Instant now = Instant.now();
    Instant periodEnd = subscription.getBillingCycle() == BillingCycle.MONTHLY
        ? now.plus(Duration.ofDays(30))
        : now.plus(Duration.ofDays(365));
    
    subscription.setPlanId(newPlan.getId());
    subscription.setStatus(SubscriptionStatus.ACTIVE);
    subscription.setCurrentPeriodStart(now);
    subscription.setCurrentPeriodEnd(periodEnd);
    
    // Clear pending upgrade state
    subscription.clearPendingUpgrade();
    subscription.clearScheduledDowngrade();
    subscription.setCancelAtPeriodEnd(false);
    subscription.setCancelledAt(null);
    
    subscriptionRepository.save(subscription);
    
    // Mark invoice as PAID
    invoiceService.markInvoicePaid(invoiceId);
    
    // Publish events
    outboxEventPublisher.save(new BillingEvent(
        type = PLAN_UPGRADED,
        fromPlan = oldPlan.getName(),
        toPlan = newPlan.getName(),
        charge = invoice.getTotalAmount()
    ));
    
    // Trigger notification
    outboxEventPublisher.save(new NotificationEvent(
        type = PLAN_UPGRADE_SUCCESS,
        tenantId = subscription.getTenantId(),
        data = { "planName": newPlan.getName(), ... }
    ));
}
```

**State Before Step 2**: `upgradePendingInvoiceId=100, planId=1 (FREE)`
**State After Step 2**: `upgradePendingInvoiceId=null, planId=2 (PRO)`

### Downgrade Flow

**Downgrade Option 1: Scheduled (Recommended)**

`POST /api/subscriptions/downgrade/free { "atPeriodEnd": true, "reason": "too expensive" }`

```java
@Transactional
public void downgradeToFreeAtPeriodEnd(DowngradeRequest request) {
    Subscription current = subscriptionRepository
        .findFirstByStatusIn([ACTIVE, TRIALING])
        .orElseThrow();
    
    Plan freePlan = planService.getPlanByName("FREE");
    
    // Do NOT change planId immediately
    // Instead, schedule the change for period end
    current.setScheduledDowngradePlanId(freePlan.getId());
    
    // If user cancels before period end, this can be cleared
    current.setCancelAtPeriodEnd(false); // Can still use Pro until period end
    subscriptionRepository.save(current);
    
    // Publish event
    outboxEventPublisher.save(new BillingEvent(
        type = PLAN_DOWNGRADE_SCHEDULED,
        oldPlan = current.getPlanId(),
        newPlan = freePlan.getId(),
        effectiveAt = current.getCurrentPeriodEnd()
    ));
}
```

**Downgrade Option 2: Immediate**

`POST /api/subscriptions/downgrade/free { "atPeriodEnd": false, "reason": "cancel access now" }`

```java
@Transactional
public void downgradeToFreeImmediately(DowngradeRequest request) {
    Subscription current = subscriptionRepository
        .findFirstByStatusIn([ACTIVE, TRIALING])
        .orElseThrow();
    
    Plan freePlan = planService.getPlanByName("FREE");
    
    // Change immediately, no refund
    current.setPlanId(freePlan.getId());
    current.setScheduledDowngradePlanId(null);
    subscriptionRepository.save(current);
    
    // Publish event + notification
    outboxEventPublisher.save(new BillingEvent(type = PLAN_DOWNGRADED_IMMEDIATE));
    outboxEventPublisher.save(new NotificationEvent(type = PLAN_DOWNGRADE_IMMEDIATE_NOTICE));
}
```

### Billing Cycle Change (Monthly ↔ Annual)

**MONTHLY → ANNUAL (Requires Payment)**

`POST /api/subscriptions/cycle { "newBillingCycle": "ANNUAL" }`

```java
@Transactional
public SubscriptionOrderResponse changeCycleToAnnual(CycleChangeRequest request) {
    Subscription current = subscriptionRepository.findCurrent();
    
    if (current.getBillingCycle() == BillingCycle.ANNUAL) {
        throw new SubscriptionException("Already on annual billing");
    }
    
    // Calculate proration for MONTHLY→ANNUAL
    ProrationResult proration = prorationService.calculateCycleChangeProration(
        currentPlan = current.getPlanId(),
        fromCycle = MONTHLY,
        toCycle = ANNUAL
    );
    // Typically: ANNUAL price is 10% cheaper than 12 × MONTHLY
    // So user gets charged (annual - (paid_months × monthly))
    
    // Create invoice for the difference
    Invoice cycleChangeInvoice = invoiceService.generateInvoice(..., proration.getChargeAmount());
    
    // Create Razorpay order
    RazorpayOrderResponse order = RazorpayPaymentGateway.createOrder(...);
    
    // Mark pending (similar to upgrade)
    current.setUpgradePendingInvoiceId(cycleChangeInvoice.getId());
    current.setUpgradePendingRazorpayOrderId(order.getId());
    current.setScheduledBillingCycle(BillingCycle.ANNUAL); // Flag for activation
    subscriptionRepository.save(current);
    
    return SubscriptionOrderResponse.builder()
        .razorpayOrderId(order.getId())
        .razorpayKeyId(razorpayKeyId)
        .amount(proration.getChargeAmount())
        .build();
}
```

**ANNUAL → MONTHLY (No Payment, Scheduled)**

```java
@Transactional
public void changeCycleToMonthly(CycleChangeRequest request) {
    Subscription current = subscriptionRepository.findCurrent();
    
    if (current.getBillingCycle() == BillingCycle.MONTHLY) {
        throw new SubscriptionException("Already on monthly billing");
    }
    
    // No payment required (moving to more frequent, shorter cycles)
    // Takes effect at next period end
    current.setScheduledBillingCycle(BillingCycle.MONTHLY);
    subscriptionRepository.save(current);
    
    // Event + notification
    outboxEventPublisher.save(new BillingEvent(
        type = CYCLE_CHANGE_SCHEDULED,
        fromCycle = ANNUAL,
        toCycle = MONTHLY,
        effectiveAt = current.getCurrentPeriodEnd()
    ));
}
```

---

## Flow

### Upgrade Flow

```
┌─────────────────────────────────────────────┐
│  User clicks "Upgrade to Pro" button        │
│  Sees price: ₹1,499/month with proration   │
└────────────────┬────────────────────────────┘
                 │
                 ▼
    ┌────────────────────────────┐
    │ POST /subscriptions/upgrade  │
    │ SubscriptionController.      │
    │ upgradeToPro(BillingCycle)  │
    └────────────┬─────────────────┘
                 │
                 ▼
    ┌────────────────────────────────────────┐
    │ SubscriptionService.initiateUpgradeToPro │
    │ • Validate no upgrade in progress       │
    │ • Fetch Pro plan details                │
    │ • Calculate proration                   │
    │ • Create OPEN invoice                   │
    │ • Create Razorpay order                 │
    │ • Set upgradePendingInvoiceId           │
    │ • Set upgradePendingPlanId              │
    │ • Publish PLAN_CHANGE_INITIATED event   │
    └────────────┬───────────────────────────┘
                 │
                 ▼
    ┌────────────────────────────────────────┐
    │ HTTP 200 + SubscriptionOrderResponse    │
    │ {                                       │
    │   razorpayOrderId: "order_abc",         │
    │   razorpayKeyId: "rzp_live_...",        │
    │   amount: 1499,                         │
    │   paise: 149900                         │
    │ }                                       │
    └────────────┬───────────────────────────┘
                 │
                 ▼ [Frontend receives response]
    ┌────────────────────────────────────────┐
    │ Frontend opens Razorpay.openCheckout() │
    │ with order ID and key                  │
    └────────────┬───────────────────────────┘
                 │
                 ▼ [User enters payment details]
    ┌────────────────────────────────────────┐
    │ Razorpay processes payment              │
    │ Returns payment_id + signature          │
    └────────────┬───────────────────────────┘
                 │
   ┌─────────────┴──────────────┐
   │                            │
   ▼                            ▼
Payment         Payment
Success         Failed
   │                 │
   ▼                 │
┌────────────────┐   │
│ Frontend calls │   │
│ POST /payments/│   │
│ verify with    │   │
│ payment_id +   │   │
│ signature      │   │
└────────┬───────┘   │
         │            │
         ▼            ▼
    ┌────────────────────────────────┐
    │ PaymentService.verify()         │
    │ • Verify Razorpay signature     │
    │ • Match to invoice              │
    │ • Call activateUpgradeAfter     │
    │   Payment(invoiceId)            │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ SubscriptionService            │
    │ .activateUpgradeAfterPayment()  │
    │ • Swap planId (FREE → PRO)     │
    │ • Reset billingPeriod           │
    │ • Clear upgradePendingFields    │
    │ • Mark invoice as PAID          │
    │ • Publish PLAN_UPGRADED event   │
    │ • Notify user                   │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Subscription state updated:     │
    │ planId = 2 (PRO)                │
    │ status = ACTIVE                 │
    │ period renewed                  │
    └────────────────────────────────┘
```

---

## Code References

| Class | Tag | Method/Purpose | Role |
|-------|-----|----------------|------|
| `Subscription` | [BIL-1] | Entity mapping | Domain object for subscription record |
| `SubscriptionService` | [BIL-14] | `getCurrentSubscription()` | Query current subscription |
| `SubscriptionService` | [BIL-14] | `previewUpgrade()` | Show proration without state change |
| `SubscriptionService` | [BIL-14] | `initiateUpgradeToPro()` | Step 1: create invoice + Razorpay order |
| `SubscriptionService` | [BIL-14] | `initiateUpgradeToEnterprise()` | Step 1: enterprise upgrade |
| `SubscriptionService` | [BIL-14] | `activateUpgradeAfterPayment()` | Step 2: swap plan after payment verified |
| `SubscriptionService` | [BIL-14] | `downgradeToFree()` | Downgrade at period end or immediately |
| `SubscriptionService` | [BIL-14] | `changeBillingCycle()` | MONTHLY ↔ ANNUAL conversion |
| `SubscriptionService` | [BIL-14] | `cancelSubscription()` | Cancel now or at period end |
| `SubscriptionController` | [BIL-40] | GET `/current` | Fetch current subscription |
| `SubscriptionController` | [BIL-40] | GET `/upgrade/preview` | Proration preview |
| `SubscriptionController` | [BIL-40] | POST `/upgrade/pro` | Initiate Pro upgrade (Step 1) |
| `SubscriptionController` | [BIL-40] | POST `/upgrade/enterprise` | Initiate Enterprise upgrade (Step 1) |
| `SubscriptionController` | [BIL-40] | POST `/downgrade/free` | Downgrade to Free |
| `SubscriptionResponse` | [BIL-56] | DTO | API response shape |
| `UpgradeRequest` | [BIL-60] | DTO | Request body for upgrades |
| `DowngradeRequest` | [BIL-61] | DTO | Request body for downgrades |
| `UpgradePreviewResponse` | [BIL-62] | DTO | Proration preview response |
| `SubscriptionOrderResponse` | [BIL-63] | DTO | Razorpay order details (Step 1 response) |

---

## Rules / Constraints

1. **planId MUST only change AFTER payment is verified** — During Step 1 (upgrade initiation), planId remains unchanged. It only swaps during Step 2 (activateUpgradeAfterPayment), after Razorpay signature is verified. If planId changed during Step 1, subscription would already reflect the new plan before payment is guaranteed. This breaks billing guarantees.

2. **upgradePendingInvoiceId MUST be cleared atomically with planId change** — If an upgrade is active (upgradePendingInvoiceId != null) and the subscription crashes mid-activation, the system must be able to detect and retry. Clearing both in same @Transactional method ensures atomicity. If one is cleared but not the other, the next activation attempt would be confused.

3. **Scheduled downgrade MUST be cancelled if upgrade succeeds** — If a user schedules a downgrade to Free at period end, then changes their mind and upgrades to Pro mid-cycle, the scheduled downgrade MUST be cleared. Otherwise, the user would upgrade to Pro and then immediately be downgraded to Free at period end (wrong behavior). Call `subscription.clearScheduledDowngrade()` whenever an upgrade is activated.

4. **cancelAtPeriodEnd=true forbids upgrades** — If a user has already scheduled cancellation at period end, they cannot upgrade (no point in paying for an upgrade if subscription is ending). The upgrade endpoint must check `if (subscription.getCancelAtPeriodEnd()) throw new SubscriptionException(...)`.

5. **Trial expirations MUST be processed by scheduled job, not by endpoint** — Do not check trial expiry on every GET /current call (O(n) query cost). Instead, a daily Quartz job (TrialExpiryJob) marks subscriptions as EXPIRED if trialEnd < now. This is batched, efficient, and decoupled from user requests.

6. **Proration MUST be bidirectional** — Upgrading from FREE to PRO charges the difference. Downgrading from PRO to FREE (immediate only) refunds the difference. The ProrationService must handle both directions. If proration is uni-directional, downgrade refunds are lost in the code.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| User attempts upgrade but subscription already has pending upgrade | `SubscriptionException("Upgrade already in progress")` | 409 Conflict | Check current subscription state before allowing new upgrade; prompt user to complete or cancel existing upgrade |
| Razorpay order creation fails (API down, invalid params) | `PaymentException` from RazorpayPaymentGateway | 503 Service Unavailable | Razorpay is temporarily unavailable; retry endpoint after service recovery; no subscription state changed |
| Payment verified but subscription cannot be found by upgradePendingInvoiceId | `ResourceException.notFound()` | 404 Not Found | Data inconsistency; subscription record deleted or upgradePendingInvoiceId corrupted; manual investigation and DB fix required |
| Subscription billing cycle is ANNUAL but user tries to downgrade to FREE (should schedule) | `SubscriptionException("Cannot downgrade annual mid-period")` | 400 Bad Request | Downgrade only allowed at period end (scheduled) for annual; frontend should hide downgrade button or show "takes effect on date X" |
| User upgrades then immediately calls cancel before payment succeeds | Subscription status still TRIALING, not yet ACTIVE | HTTP 200 (cancel succeeds) | Cancel clears upgradePendingInvoiceId; upgrade reverted; trial/plan unchanged; Razorpay order abandoned (will expire) |
| BillingCycleJob tries to bill a subscription with pendingUpgrade in flight | Job skips this subscription (no invoice generated) | N/A (internal) | Upgrade flow must complete or be cancelled before next billing cycle; user must verify payment or retry |
| Database concurrent update: two requests try to update same subscription simultaneously | `OptimisticLockException` (version mismatch) | 409 Conflict | Retry the request; Hibernate's @Version field prevents lost updates |

---

## Edge Cases

- **Concurrency**: Two simultaneous requests to upgrade and downgrade. The second one to execute should fail ("Upgrade already in progress"). Database row-level lock + version field handles this.

- **Timezone**: `trialEnd`, `currentPeriodStart`, `currentPeriodEnd` are all TIMESTAMPTZ (UTC). BillingCycleJob compares `currentPeriodEnd < Instant.now()` in UTC, no timezone confusion.

- **Tenant Isolation**: Subscriptions are stored in tenant schema (e.g., s_456.subscriptions). Query via `subscriptionRepository.findCurrent()` automatically scoped via TenantContext. No cross-tenant leakage.

- **Empty State**: New tenant has no subscriptions initially. POST /signup creates a subscription with status=TRIALING, trial dates set, planId=FREE. GET /current might return null (404) if subscription never created (should be impossible after signup, but could happen if migration missing).

- **Trial Conversion Deadline**: If trial expires while upgrade is in flight (upgradePendingInvoiceId set but not yet paid), TrialExpiryJob marks subscription as EXPIRED. User cannot pay the upgrade invoice for an EXPIRED subscription. The upgrade should be cancelled explicitly before trial expiry.

- **Refund Disputes**: If user pays for upgrade but immediately requests refund from Razorpay, payment status reverts from CAPTURED to AUTHORIZED/FAILED. PaymentService.handleRefund() must downgrade subscription back to previous plan. If not handled, tenant keeps Pro access but Razorpay refunded them.

- **Plan Availability**: If a Plan is soft-deleted (DELETE plan) but a subscription still references planId, queries fail (no plan found). Must migrate subscriptions to another plan before deleting.

---

## Known Issues / Limitations

1. **No upgrade abandonment tracking** — If user initiates upgrade but never completes payment (Razorpay order expires after 15 min), the upgradePendingInvoiceId is never cleared. Future manual cleanup is needed, or a scheduled job to void stale pending invoices.

2. **No partial proration support** — If user downgrades mid-cycle and then upgrades again (yo-yo scenario), proration is calculated only from current date, not accounting for previous downgrades in same cycle. Accurate proration for multiple changes requires event replay (complex).

3. **No credit system** — Downgrade refunds are not tracked or carried forward as credits. If tenant downgrades from Pro (paid ₹1,000 for month) to Free on day 15, they should get ₹500 credit for the remaining 2 weeks. Currently no credit; tenant just loses money (by design for immediate downgrade).

4. **Billing cycle change at period end is manual trigger** — `BillingCycleJob` checks `scheduledBillingCycle` at period renewal and applies it, but there's no guarantee the job runs at exactly period end. If job runs 1 hour late, the cycle change happens 1 hour after period end (customer might notice).

5. **No grandfathering of pricing** — If a plan price changes (e.g., Pro drops from ₹1,499 to ₹999), all existing subscriptions get the new price at renewal. No per-subscription price locking or legacy pricing tiers.

---

## Future Improvements

1. Implement upgrade abandonment cleanup — Scheduled job runs hourly, finds invoices older than 1 hour with pending upgrade, voids them, clears upgradePendingInvoiceId. Prevents stale pending state.

2. Add proration history tracking — Store each proration calculation (dated) so yo-yo scenarios can be replayed and accurate refunds calculated.

3. Implement credit system — On downgrade, store refund as credit on Tenant entity. Credit applied to next invoice automatically, or user can request refund.

4. Add grandfathering of pricing — When Plan price changes, existing subscriptions get a "legacy_price" field. Locking prevents surprise price increases at renewal.

5. Implement dunning flow — After 3 failed payment retries, send automated email offering payment plan options, then downgrade to Free if user doesn't pay within 14 days.

6. Add usage-based overage charges — If Pro plan includes 1,000 API calls/month and tenant exceeds, generate overage invoice automatically. Currently only fixed pricing supported.

---

## Related Documents
- [invoice.md](./invoice.md) — Invoice created for subscription charges and upgrades
- [payment-processing.md](./payment-processing.md) — Payment capture and Razorpay integration
- [plan-change-flow.md](./plan-change-flow.md) — Related Phase 4 document on specific upgrade scenarios
- [proration.md](./proration.md) — Detailed proration calculation logic
- [system-design.md](../01-architecture/system-design.md) — Architecture overview
- [request-flow.md](../01-architecture/request-flow.md) — HTTP filter chain
- [outbox-pattern.md](../01-architecture/outbox-pattern.md) — Event delivery guarantees
- [event-flow.md](../01-architecture/event-flow.md) — Events published by subscription changes
- [scheduler-jobs.md](../09-jobs/scheduler-jobs.md) — TrialExpiryJob, SubscriptionExpiryJob, BillingCycleJob
