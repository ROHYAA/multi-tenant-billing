---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - subscription
  - plan-change
  - upgrade-flow
  - payment-flow
  - razorpay
  - 2-step-payment
  - state-machine
  - billing
related_documents:
  - ./subscription-lifecycle.md
  - ./payment-processing.md
  - ./proration.md
  - ../06-api/billing-api.md
  - ../01-architecture/outbox-pattern.md
---

# Plan Change Flow (2-Step Upgrade & Downgrade)

## Executive Summary

**Plan Change Flow** is the complete lifecycle for upgrading/downgrading a subscription, split into two critical phases: **(1) Initiate** (creates invoice + Razorpay order, doesn't change plan yet), and **(2) Verify** (user completes Razorpay checkout, payment verifies signature, plan activates). This two-phase design ensures the plan never changes without payment verification—preventing data corruption if the app crashes mid-checkout. Downgrades can be immediate or scheduled for the period end. The system calculates proration credits mid-cycle and enforces strict invariants (no concurrent changes, cancellation cancels pending upgrades).

---

## Context / Problem

### Historical Problem: Coupled Billing & Payment

**Bad design (pre-refactor):**
```java
// OLD: Changes subscription immediately, no payment guarantee
public void upgradeToPlan(Plan target) {
    subscription.setPlanId(target.getId());  // ← Changed NOW
    subscription.save();
    try {
        createRazorpayOrder(...);  // ← May fail
    } catch (Exception e) {
        // Too late — subscription already changed, no rollback
    }
}
```

**Problem:**
- If Razorpay API fails after plan change, data is inconsistent (user has new plan but payment wasn't created)
- If the app crashes during payment, subscription is in limbo
- No audit trail of what the user intended vs what actually happened

### Solution: 2-Step Payment Model

```
Step 1: Initiate (Create Invoice + Order)
  ├─ Verify subscription can be upgraded (not PAST_DUE, etc.)
  ├─ Calculate proration credit
  ├─ Create OPEN invoice (not yet paid)
  ├─ Create Razorpay order
  ├─ Mark subscription.upgradePendingInvoiceId = invoiceId  ← Pending state
  ├─ Mark subscription.upgradePendingPlanId = targetPlanId  ← Intent recorded
  └─ Return razorpayOrderId to frontend

  ⏳ User opens Razorpay checkout modal (may abandon)

Step 2: Verify (Activate After Payment)
  ├─ User completes checkout, frontend gets payment confirmation
  ├─ Frontend calls POST /api/payments/verify with Razorpay signature
  ├─ PaymentService verifies HMAC signature with Razorpay
  ├─ If valid: capture payment, mark payment SUCCEEDED
  ├─ PaymentService calls SubscriptionService.activateUpgradeAfterPayment(invoiceId)
  ├─ SubscriptionService swaps planId = upgradePendingPlanId  ← NOW it changes
  ├─ Clear all pending upgrade fields (upgradePendingInvoiceId = null)
  ├─ Fire PLAN_UPGRADED event
  └─ Return 200 OK to frontend
```

### Key Insight: Plan Changes Between Steps

The **Subscription.planId is never updated in Step 1**. It only changes in Step 2 after payment is verified. This ensures:
- If user abandons checkout, subscription remains unchanged (no orphaned state)
- If payment signature is invalid, subscription is not corrupted
- Audit trail clearly shows when the plan actually changed

---

## Dependencies

### Inbound (What calls this)
- **SubscriptionController** → `upgradeToPro()`, `upgradeToEnterprise()`, `downgradeToFree()`, `changeBillingCycle()` — HTTP endpoints
- **PaymentService** → `activateUpgradeAfterPayment(invoiceId)` — after payment verification
- **SubscriptionExpiryJob** → automatic downgrade on period expiry

### Outbound (What this calls)
- **InvoiceService** → `createInvoiceForUpgrade()` — create OPEN invoice for the upgrade
- **PaymentService** → `processPayment(invoiceId)` — create Razorpay order, idempotent
- **ProrationService** → `buildPreview()`, `calculateCredit()` — compute upgrade amounts
- **PlanService** → `getPlanById()`, `getPlanByName()` — resolve plan details
- **OutboxEventPublisher** → publish audit logs and notifications
- **TenantContext** → verify schema/tenant isolation

### Configuration
- `razorpay.key-id` — Razorpay account public key (for frontend checkout)
- `app.notification.*` — Email templates for upgrade/downgrade notifications
- `app.billing.cycle.monthly-days` — Default 30 (for proration calculation)
- `app.billing.cycle.annual-days` — Default 365

---

## Design / Implementation

### State Machine: Pending Upgrade Fields

The **Subscription entity** has three fields that track pending upgrade state:

```java
@Column(name = "upgrade_pending_invoice_id")
private Long upgradePendingInvoiceId;  // NULL or invoice ID awaiting payment

@Column(name = "upgrade_pending_plan_id")
private Long upgradePendingPlanId;  // NULL or target plan ID

@Column(name = "upgrade_pending_razorpay_order_id")
private String upgradePendingRazorpayOrderId;  // NULL or order ID for re-checkout
```

**Invariants:**
- All three are NULL when no upgrade is pending (normal state)
- All three are non-NULL when upgrade is pending (mid-checkout state)
- Cannot initiate new upgrade if any are non-NULL (re-initiate or abandon first)
- Cancelled if user calls `/downgrade/free` before completing payment

### Step 1: Initiate Upgrade

**Endpoint:** `POST /api/v1/subscriptions/upgrade/pro` or `/upgrade/enterprise`

**Request:**
```json
{
  "billingCycle": "MONTHLY"  // or ANNUAL
}
```

**Flow:**
```java
@Transactional
public SubscriptionOrderResponse initiateUpgradeToPro(UpgradeRequest request) {
    Plan proPlan = planService.getPlanByName("PRO");
    
    Subscription subscription = requireActiveOrTrialing();
    validateNotUpgradingAlready(subscription);  // ← Check not already pending
    validateNotPastDue(subscription);  // ← Can't upgrade if delinquent
    
    // Calculate what user will be charged
    UpgradePreviewResponse preview = prorationService.buildPreview(
        subscription, proPlan.getId(), request.getBillingCycle());
    
    // Create OPEN invoice (not yet paid)
    Invoice invoice = invoiceService.createInvoiceForUpgrade(
        subscription, proPlan, request.getBillingCycle(), 
        preview.getProrationCredit(), preview.getChargeAmount());
    
    // Create Razorpay order
    OrderResponse razorpayOrder = paymentService.processPayment(invoice.getId());
    
    // Mark subscription as pending upgrade (idempotent — same order if retried)
    subscription.setUpgradePendingInvoiceId(invoice.getId());
    subscription.setUpgradePendingPlanId(proPlan.getId());
    subscription.setUpgradePendingRazorpayOrderId(razorpayOrder.getOrderId());
    subscriptionRepository.save(subscription);
    
    // Audit log
    auditLog(AuditAction.SUBSCRIPTION_UPGRADE_INITIATED, subscription);
    
    // Return to frontend (contains razorpayOrderId, keyId, amount)
    return SubscriptionOrderResponse.builder()
        .razorpayOrderId(razorpayOrder.getOrderId())
        .razorpayKeyId(razorpayKeyId)
        .amount(razorpayOrder.getAmount())  // in paise
        .currency(razorpayOrder.getCurrency())
        .build();
}
```

**Response (200 OK):**
```json
{
  "razorpayOrderId": "order_1234567890",
  "razorpayKeyId": "rzp_live_...",
  "amount": 29900,  // ₹299 in paise
  "currency": "INR"
}
```

**Frontend behavior:**
```javascript
// Frontend uses response to open Razorpay checkout
const options = {
    key: response.razorpayKeyId,
    amount: response.amount,
    currency: response.currency,
    order_id: response.razorpayOrderId,
    handler: (paymentResponse) => {
        // User completed checkout, got signature from Razorpay
        fetch('/api/v1/payments/verify', {
            method: 'POST',
            body: JSON.stringify({
                razorpayOrderId: response.razorpayOrderId,
                razorpayPaymentId: paymentResponse.razorpay_payment_id,
                razorpaySignature: paymentResponse.razorpay_signature
            })
        });
    },
    onDismiss: () => {
        // User abandoned checkout
        // Subscription still has upgradePendingInvoiceId set
        // User can call retry endpoint or start over
    }
};
Razorpay(options).open();
```

### Step 2: Verify Payment & Activate Upgrade

**Endpoint:** `POST /api/v1/payments/verify`

**Request (from Razorpay):**
```json
{
  "razorpayOrderId": "order_1234567890",
  "razorpayPaymentId": "pay_...",
  "razorpaySignature": "9ef4dffbfd84f1318f6739a3ce19f9d85851857ae648f114332d8401e0949a3d"
}
```

**Flow:**
```java
@Transactional
public PaymentResponse verifyAndCapturePayment(VerifyPaymentRequest request) {
    
    Payment payment = paymentRepository.findByRazorpayOrderId(request.getRazorpayOrderId())
        .orElseThrow(() -> ResourceException.notFound("Payment", request.getRazorpayOrderId()));
    
    // ✅ Verify signature (critical security check)
    boolean valid = paymentGateway.verifyPaymentSignature(
        request.getRazorpayOrderId(),
        request.getRazorpayPaymentId(),
        request.getRazorpaySignature());
    
    if (!valid) {
        throw PaymentException.invalidSignature();  // ← Reject forgery
    }
    
    // ✅ Capture the payment (move from authorization to captured)
    long amountInPaise = payment.getAmount()
        .multiply(BigDecimal.valueOf(100))
        .longValue();
    paymentGateway.capturePayment(request.getRazorpayPaymentId(), amountInPaise);
    
    // ✅ Mark payment succeeded
    payment.setStatus(PaymentStatus.SUCCEEDED);
    payment.setRazorpayPaymentId(request.getRazorpayPaymentId());
    payment.setRazorpaySignature(request.getRazorpaySignature());
    paymentRepository.save(payment);
    
    // ✅ Mark invoice as paid
    Invoice invoice = payment.getInvoice();
    invoice.setStatus(InvoiceStatus.PAID);
    invoiceRepository.save(invoice);
    
    // ✅ ACTIVATE THE UPGRADE (the big moment!)
    subscriptionService.activateUpgradeAfterPayment(invoice.getId());
    
    // Return success
    return PaymentResponse.builder()
        .paymentId(payment.getId())
        .status(PaymentStatus.SUCCEEDED)
        .amount(payment.getAmount())
        .currency(payment.getCurrency())
        .build();
}
```

**Key line: `subscriptionService.activateUpgradeAfterPayment(invoice.getId())`**

```java
@CacheEvict(value = "dashboard", allEntries = true)
@Transactional
public void activateUpgradeAfterPayment(Long invoiceId) {
    
    // Find the subscription linked to this paid invoice
    Subscription subscription = subscriptionRepository
        .findByUpgradePendingInvoiceId(invoiceId)
        .orElseThrow(() -> ResourceException.notFound(
            "Subscription with pending upgrade for invoice", invoiceId));
    
    Plan oldPlan = planService.getPlanById(subscription.getPlanId());
    Plan newPlan = planService.getPlanById(subscription.getUpgradePendingPlanId());
    
    Instant now = Instant.now();
    Instant periodEnd = subscription.getBillingCycle() == BillingCycle.MONTHLY
        ? now.plus(Duration.ofDays(30))
        : now.plus(Duration.ofDays(365));
    
    // ✅ CHANGE THE PLAN (only happens here, never in Step 1)
    subscription.setPlanId(newPlan.getId());
    subscription.setStatus(SubscriptionStatus.ACTIVE);
    subscription.setCurrentPeriodStart(now);
    subscription.setCurrentPeriodEnd(periodEnd);
    
    // ✅ Clear pending upgrade state
    subscription.clearPendingUpgrade();  // Sets all upgrade fields to NULL
    
    // ✅ Upgrade cancels any scheduled downgrade
    subscription.clearScheduledDowngrade();
    subscription.setCancelAtPeriodEnd(false);
    subscription.setCancelledAt(null);
    
    subscriptionRepository.save(subscription);
    
    log.info("Upgrade activated — from={}, to={}, cycle={}, tenantId={}",
        oldPlan.getName(), newPlan.getName(),
        subscription.getBillingCycle(), TenantContext.getTenantId());
    
    // ✅ Fire PLAN_UPGRADED event (triggers notification email)
    fireUpgradeEvent(NotificationEvent.PLAN_UPGRADED, oldPlan, newPlan,
        subscription, invoiceId);
    
    // ✅ Audit log
    auditLog(AuditAction.SUBSCRIPTION_UPDATED, subscription,
        Map.of("planId", oldPlan.getId() + "→" + newPlan.getId()));
}
```

**Response (200 OK):**
```json
{
  "paymentId": 12345,
  "status": "SUCCEEDED",
  "amount": 299.00,
  "currency": "INR"
}
```

Frontend should:
1. Show success message ("Your plan has been upgraded!")
2. Refresh subscription data via `GET /api/v1/subscriptions/current`
3. Update dashboard to reflect new plan features

---

## Downgrade Flow

### Immediate Downgrade (to FREE)

**Endpoint:** `POST /api/v1/subscriptions/downgrade/free`

**Request:**
```json
{
  "atPeriodEnd": false,
  "reason": "Too expensive"
}
```

**Flow:**
```java
@Transactional
public SubscriptionResponse downgradeToFree(DowngradeRequest request) {
    
    Subscription subscription = requireActiveOrTrialing();
    Plan freePlan = planService.getPlanByName("FREE");
    
    if (subscription.getPlanId().equals(freePlan.getId())) {
        throw SubscriptionException.alreadyOnFreePlan();
    }
    
    // ✅ Clear any pending upgrade (downgrade wins)
    if (subscription.getUpgradePendingInvoiceId() != null) {
        voidPendingUpgrade(subscription);  // Void the OPEN invoice
    }
    
    if (!request.isAtPeriodEnd()) {
        // Immediate downgrade
        subscription.setPlanId(freePlan.getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        // New period starts now
        subscription.setCurrentPeriodStart(Instant.now());
        subscription.setCurrentPeriodEnd(Instant.now().plus(Duration.ofDays(30)));
    } else {
        // Scheduled downgrade (at period end)
        subscription.setScheduledDowngradePlanId(freePlan.getId());
        subscription.setCancelAtPeriodEnd(true);
        // planId doesn't change until SubscriptionExpiryJob runs
    }
    
    subscriptionRepository.save(subscription);
    
    // Fire notification
    fireDowngradeEvent(NotificationEvent.PLAN_DOWNGRADED);
    
    return mapToSubscriptionResponse(subscription);
}
```

### Downgrade at Period End

If `atPeriodEnd=true`, the subscription continues with the current plan until `currentPeriodEnd`, then automatically downgrades. The **SubscriptionExpiryJob** (runs hourly) detects this:

```java
@Scheduled(cron = "0 * * * * ?")  // Every hour
public void expireSubscriptions() {
    List<Subscription> expiring = subscriptionRepository
        .findAllByCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(Instant.now());
    
    for (Subscription subscription : expiring) {
        Plan downgradeTo = planService.getPlanById(
            subscription.getScheduledDowngradePlanId());
        
        subscription.setPlanId(downgradeTo.getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setCancelAtPeriodEnd(false);
        subscription.setScheduledDowngradePlanId(null);
        subscriptionRepository.save(subscription);
        
        // Fire notification
        outboxEventPublisher.publishEvent(
            NotificationEvent.SUBSCRIPTION_DOWNGRADED, ...);
    }
}
```

---

## Billing Cycle Change

**Endpoint:** `POST /api/v1/subscriptions/cycle`

**Request:**
```json
{
  "newBillingCycle": "ANNUAL"  // or MONTHLY
}
```

**Cases:**

### Case 1: MONTHLY → ANNUAL (Upgrade cycle, requires payment)

```
Current: MONTHLY plan ₹299/month, 20 days left in period
New:     ANNUAL plan ₹2999/year

Calculation:
  Credit = (20 / 30) × ₹299 = ₹199.33
  Charge = ₹2999 - ₹199.33 = ₹2799.67
```

**Returns:** `SubscriptionOrderResponse` (same as plan upgrade) → user pays via Razorpay

### Case 2: ANNUAL → MONTHLY (Downgrade cycle, no payment)

```
Current: ANNUAL plan ₹2999/year, 100 days left in period
New:     MONTHLY plan ₹299/month

Calculation:
  No refund (sunk cost). Next period starts at current_period_end.
  Charge = ₹0
```

**Returns:** `SubscriptionResponse` (scheduled, takes effect at period end)

---

## Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        STEP 1: INITIATE                             │
└─────────────────────────────────────────────────────────────────────┘

Frontend                            Backend (Subscription Module)
   │                                          │
   ├─ GET /upgrade/preview                    ├─ Get current subscription
   │  (optional — for UX)    ─────────────→  ├─ Load current + target plan
   │                                          ├─ Call ProrationService
   ├─ Receive preview        ←─────────────  ├─ Return credit + charge breakdown
   │                                          │
   │                                          └─────────────────────────────────
   ├─ User clicks Upgrade                    (No DB changes yet)
   │  POST /upgrade/pro       ─────────────→  ├─ Validate subscription
   │  { billingCycle: ... }                   ├─ Check not already upgrading
   │                                          ├─ Create OPEN invoice (V20)
   │                                          ├─ Create Razorpay order
   │                                          ├─ Set subscription.upgradePendingInvoiceId
   │                                          ├─ Set subscription.upgradePendingPlanId
   │                                          ├─ Publish audit log
   ├─ Receive razorpayOrderId ←──────────   ├─ Save subscription
   ├─ razorpayKeyId                          │
   ├─ amount (paise)                         └─────────────────────────────────
   │                                       (Subscription.planId NOT changed)
   ├─ Open Razorpay modal
   │  (may abandon here)
   │
   ┌─────────────────────────────────────────────────────────────────────┐
   │                        USER CHECKOUT                                │
   │  Razorpay modal handles card entry, 2FA, etc.                      │
   └─────────────────────────────────────────────────────────────────────┘
   │
   ├─ User completes payment
   │  Razorpay returns:
   │  - razorpay_payment_id
   │  - razorpay_order_id
   │  - razorpay_signature
   │
   ├─ POST /payments/verify  ─────────────→  ┌─────────────────────────────────┐
   │ { orderId, paymentId,                   │      STEP 2: VERIFY & ACTIVATE  │
   │   signature }                           └─────────────────────────────────┘
   │                                          │
   │                                          ├─ Verify HMAC signature
   │                                          │  (validate not forged)
   │                                          ├─ Capture payment with Razorpay
   │                                          ├─ Mark payment SUCCEEDED
   │                                          ├─ Mark invoice PAID
   │                                          │
   │                                          ├─ [CRITICAL] Call activateUpgradeAfterPayment()
   │                                          │    └─ GET subscription by pending invoice
   │                                          │    └─ Swap subscription.planId = target
   │                                          │    └─ Set new billing period
   │                                          │    └─ Clear all upgrade_pending_* fields
   │                                          │    └─ Clear any scheduled downgrade
   │                                          │    └─ Publish PLAN_UPGRADED event
   │                                          │    └─ Audit log
   │                                          │
   ├─ Receive 200 OK         ←──────────────├─ Save subscription
   │                                          │
   ├─ Show success message                   └─────────────────────────────────
   ├─ Refresh dashboard                    (Subscription.planId changed HERE)
   │
   └─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    DOWNGRADE: At Period End                         │
│  (if atPeriodEnd=true in downgrade request)                        │
└─────────────────────────────────────────────────────────────────────┘

SubscriptionExpiryJob (hourly)    Backend
         │                            │
         ├─ Poll subscriptions    ────→ ├─ Query WHERE cancelAtPeriodEnd=true
         │  with cancelAtPeriodEnd       │   AND currentPeriodEnd < now
         │  and currentPeriodEnd passed  │
         │                            ├─ For each: swap planId to FREE
         │                            ├─ Clear cancelAtPeriodEnd flag
         │                            ├─ Publish PLAN_DOWNGRADED event
         │                            ├─ Audit log
         │                            │
         ├─ Job completes        ←──── ├─ Save all subscriptions
         │
         └────────────────────────────────────────────────────────────────
```

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `SubscriptionService` | [SUB-1] | `initiateUpgradeToPro()` | Step 1: create invoice + order, set pending state |
| `SubscriptionService` | [SUB-2] | `activateUpgradeAfterPayment()` | Step 2: swap planId after payment verified |
| `SubscriptionService` | [SUB-3] | `downgradeToFree()` | Downgrade immediately or at period end |
| `SubscriptionController` | [SUB-4] | `upgradeToPro()`, `upgradeToEnterprise()` | HTTP endpoints for Step 1 |
| `PaymentService` | [SUB-5] | `verifyAndCapturePayment()` | Step 2: verify signature, capture, activate upgrade |
| `PaymentService` | [SUB-6] | `processPayment()` | Create Razorpay order, idempotent |
| `Subscription` | [SUB-7] | `upgradePendingInvoiceId`, `upgradePendingPlanId` | Pending upgrade state fields |
| `ProrationService` | [SUB-8] | `buildPreview()` | Calculate credit + charge for preview |
| `InvoiceService` | [SUB-9] | `createInvoiceForUpgrade()` | Create OPEN invoice for upgrade |
| `SubscriptionExpiryJob` | [SUB-10] | `expireSubscriptions()` | Hourly job that applies scheduled downgrades |

---

## Rules / Constraints

1. **Plan ID Never Changes in Step 1** — `subscription.planId` is only updated in `activateUpgradeAfterPayment()`, never during `initiateUpgrade()`. If Step 2 fails or is abandoned, subscription remains unchanged.

2. **No Concurrent Plan Changes** — If `upgradePendingInvoiceId != NULL`, cannot initiate another upgrade. User must complete, abandon, or cancel the first one. Prevents orphaned invoices and confusing state.

3. **Razorpay Signature Verification MANDATORY** — Before capturing any payment, `PaymentService.verifyAndCapturePayment()` MUST verify the HMAC signature. Forged signatures are rejected immediately with `PaymentException.invalidSignature()`.

4. **Upgrade Cancels Scheduled Downgrade** — If a user schedules downgrade with `atPeriodEnd=true`, then upgrades before the period end, the scheduled downgrade is cleared and upgrade wins. Invariant: cannot have both pending.

5. **Downgrade Voids Pending Upgrade** — If a user initiates upgrade then downgrades before payment, the pending upgrade invoice is voided and subscription reverts to original state. Prevents orphaned invoices.

6. **Proration Credit Never Negative** — The charge is `max(targetPlanPrice - credit, 0)`. If credit exceeds target price, no refund—user pays $0 (next billing cycle is fresh). Never negative (no overpayment).

7. **Billing Period Renews on Upgrade** — When `activateUpgradeAfterPayment()` executes, `currentPeriodStart = now` and `currentPeriodEnd = now + cycle duration` (30 days for MONTHLY, 365 for ANNUAL). The user gets a fresh period from the upgrade moment.

8. **Razorpay Minimum: ₹1** — If computed charge is > 0 but rounds to < 1 paise (edge case: 1 day left in period), the charge is bumped to ₹1 to meet Razorpay's minimum. Documented in ProrationService.

---

## Failure Scenarios

| Scenario | Exception | HTTP | Recovery |
|---|---|---|---|
| User starts upgrade then cancels Razorpay modal | (None — user just doesn't call Step 2) | N/A | Subscription remains unchanged. Pending fields set but harmless. User can retry with same orderId (idempotent) or start fresh. |
| Invalid Razorpay signature (forged) | `PaymentException.invalidSignature()` | 400 Bad Request | Payment is rejected, subscription.planId not changed. User must restart checkout. |
| Razorpay API unreachable during capture | `PaymentGatewayException` | 503 Service Unavailable | Payment stays PENDING, subscription upgrade not activated. Retry handler attempts capture later (see retry-mechanisms.md). |
| User already has pending upgrade, tries again | `SubscriptionException` | 409 Conflict | Reject with "upgrade already in progress". User must complete, abandon, or downgrade first. |
| App crashes between verify signature + activateUpgrade | (Process crash) | N/A | Outbox pattern ensures event is published. SubscriptionService.activateUpgradeAfterPayment() is idempotent (finds by upgradePendingInvoiceId). Next request retries or manual recovery runs it. |
| Invoice not found by pending invoice ID | `ResourceException.notFound()` | 404 Not Found | Data integrity error — indicates database corruption or orphaned subscription state. Escalate to database team. |
| Subscription.planId changed externally during Step 1 | `SubscriptionException` | 409 Conflict | Detected on Step 2: `getPlanById(subscription.getPlanId())` returns different plan than recorded in `upgradePendingPlanId`. Reject to prevent invalid state. |
| Subscription PAST_DUE, user tries upgrade | `SubscriptionException.pastDueCannotUpgrade()` | 403 Forbidden | User must pay overdue invoice first. Enforce business rule: delinquent tenants cannot change plans. |

---

## Edge Cases

- **Concurrency: Two upgrade requests arrive simultaneously** — Database UNIQUE constraint on subscription ID prevents double-write. Second request is rejected with "upgrade already in progress". Frontend should debounce button.

- **Concurrency: Upgrade + Downgrade race** — Downgrade voids pending upgrade (clears fields). If they happen milliseconds apart, last-write-wins on the subscription update. Downgrade should check `upgradePendingInvoiceId` and void if present.

- **Timezone: Billing period end calculation** — `currentPeriodEnd = now + duration` uses Instant (UTC). "30 days" is literal, not calendar month. Edge case: upgrade on Jan 31 + 30 days = Feb 28/29. Accepted — next period calculated fresh.

- **Tenant isolation: Verify tenant context** — `activateUpgradeAfterPayment()` must verify subscription's tenant = `TenantContext.getTenantId()`. If mismatch, throw security exception. See cross-tenant-safety.md.

- **Cross-module: Invoice state consistency** — If InvoiceService creates invoice but fails mid-transaction, subscription's `upgradePendingInvoiceId` may point to non-existent invoice. Transactional guarantees prevent this (all-or-nothing). If it happens, manual intervention required.

- **Proration: Last day of period** — If user upgrades with 1 day left, `credit = (1 / 30) × monthlyPrice = ~10 cents`. Charge is `targetPrice - 10c`, rounded up to ₹1 if needed. Edge case but handled.

- **Scheduled downgrade + trial ending** — If subscription is TRIALING with scheduled downgrade, trial end may occur first. Job order matters. See subscription-lifecycle.md for priority.

---

## Known Issues / Limitations

1. **No Upgrade Abandonment UI** — If user abandons Razorpay modal, `upgradePendingInvoiceId` remains set but is harmless. Frontend should show "clear pending upgrade" or "retry" buttons, but currently doesn't. Invoice is voided after 24h by job (future).

2. **No Refund for Downgrade** — Downgrading mid-cycle does not refund unused period cost. User loses that money. Documented but may frustrate customers. See proration.md for potential refund feature.

3. **Cycle Change Only MONTHLY ↔ ANNUAL** — Cannot change to custom cycles or paused billing. Future feature: allow 1-month, 3-month, 6-month options.

4. **Razorpay Order ID Expires** — Razorpay orders expire after 10 minutes. If user abandons for 15 mins and retries with same orderId, Razorpay rejects it. Frontend must detect and create new order (call Step 1 again).

5. **No Bulk Plan Changes** — Only one subscription per tenant. Cannot upgrade multiple subscriptions or team members independently.

6. **Proration Does Not Refund** — Credit is applied as discount only. If credit > target price, no overpayment. Refunds not supported. See proration.md.

---

## Future Improvements

1. **Subscription Management UI** — Build a component showing: current plan, pending upgrade status, scheduled downgrade, billing date, next amount due. Allow users to cancel/retry upgrades.

2. **Refund Support** — Allow downgrade refunds (prorated). Requires new Refund entity, Razorpay refund API integration, accounting reconciliation. Risk: abuse (upgrade/downgrade repeatedly for refunds).

3. **Upgrade Retry Handler** — If Razorpay order expires, automatically create new order instead of failing. Improves UX for slow users.

4. **Plan Recommendations** — Analyze usage, suggest upgrades. "You've used 90% of your monthly limit — consider upgrading to Pro."

5. **Custom Billing Cycles** — Support 1-month, 3-month, 6-month, 2-year cycles (beyond MONTHLY/ANNUAL). Requires proration formula updates.

6. **Pause Subscription** — Allow users to pause (preserve trial, no charges). Requires new SubscriptionStatus.PAUSED.

7. **Annual Prepaid Discounts** — Offer % discount if user switches to ANNUAL. Implement via discountPercentage field on Plan or coupon system.

8. **Plan Change Notifications in App** — Toast: "Your plan has been upgraded! Enjoy Pro features now." Currently only email.

---

## Related Documents

- [subscription-lifecycle.md](./subscription-lifecycle.md) — Full subscription state machine (TRIALING, ACTIVE, PAST_DUE, CANCELLED, etc.)
- [payment-processing.md](./payment-processing.md) — Razorpay integration, capture, refund details
- [proration.md](./proration.md) — Proration math explained (credit calculation, edge cases)
- [billing-api.md](../06-api/billing-api.md) — Subscription endpoints reference
- [outbox-pattern.md](../01-architecture/outbox-pattern.md) — Event reliability for notifications
- [cross-tenant-safety.md](../02-multi-tenancy/cross-tenant-safety.md) — Tenant isolation in billing operations
