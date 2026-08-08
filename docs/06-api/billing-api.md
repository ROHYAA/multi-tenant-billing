---
version: 1.0
date: 2026-05-14
author: Manoj Pandi
status: Production Ready
tags:
  - api
  - billing
  - subscriptions
  - payments
  - invoices
  - razorpay
related_documents:
  - ../05-platform-billing/subscription-lifecycle.md
  - ../05-platform-billing/payment-processing.md
  - ../05-platform-billing/invoice.md
  - ./error-handling.md
  - ./auth-api.md
---

# Billing API

## Executive Summary

The Billing API manages subscription lifecycle (upgrade, downgrade, cycle changes), invoice management, Razorpay payment processing, and usage tracking. All endpoints require `BILLING_MANAGE` permission except for `GET /current` (open to all authenticated users) and `GET /usage/limits` (open to all authenticated users). This document covers request/response formats, state transitions, payment flows, and integration patterns.

---

## Context & Problem

### Billing Model

MTBS uses **monthly/annual recurring billing** with **proration**, **2-step payments**, and **scheduled changes**:

| Concept | Meaning |
|---------|---------|
| **Plan** | Fixed tier (FREE, PRO, ENTERPRISE) with pricing | 
| **Billing Cycle** | Renewal frequency (MONTHLY or ANNUAL) |
| **Subscription** | User's current plan + billing cycle + trial/active/cancelled state |
| **Proration** | Credit for unused days when upgrading/downgrading mid-cycle |
| **Scheduled Changes** | Downgrade/cycle change queued for period end (no immediate cost) |
| **Invoice** | Billing record generated for subscription renewal or manual upcharges |
| **Payment** | 2-step Razorpay transaction: order → verify → capture |

### Two-Step Upgrade Flow

```
User clicks "Upgrade" button
  ↓
Step 1: Frontend calls POST /upgrade/pro
  → Creates invoice (DRAFT)
  → Creates Razorpay order
  → Returns razorpayOrderId, keyId, amount
  → Plan NOT changed yet ✓ (critical)
  → Frontend opens Razorpay modal
  ↓
User completes payment
  ↓
Step 2: Frontend calls POST /payments/verify
  → Validates Razorpay signature
  → Marks invoice PAID
  → Marks payment SUCCEEDED
  → NOW changes subscription.planId ✓
  → Returns to dashboard
```

**Why 2-step?** Prevents "subscription upgraded but payment failed" scenarios.

---

## Dependencies

### Inbound (Who calls billing endpoints)

- **Frontend (React)** — Dashboard, billing page
- **Razorpay webhook** → `RazorpayWebhookController` (asynchronous payment notifications)
- **Quartz scheduler** — Jobs that auto-generate invoices (BillingCycleJob)

### Outbound (What billing depends on)

- `SubscriptionService` — Subscription state management, upgrades, downgrades
- `InvoiceService` — Invoice generation, voiding, PDF rendering
- `PaymentService` — Razorpay integration, payment verification, retry logic
- `UsageService` — Usage tracking, limit enforcement
- `PlanService` — Plan pricing, features, limits
- `RazorpayGateway` — Razorpay API client (order creation, refunds)
- `InvoicePdfService` — PDF generation using Apache FOP/Thymeleaf

### Configuration

```yaml
razorpay:
  key-id: ${RAZORPAY_KEY_ID}
  key-secret: ${RAZORPAY_KEY_SECRET}
  currency: INR
  webhook-secret: ${RAZORPAY_WEBHOOK_SECRET}

invoice:
  pdf-enabled: true
  template-path: classpath:templates/invoices/
  s3-bucket: ${AWS_S3_BUCKET_INVOICES}
  
billing:
  proration-enabled: true
  min-upgrade-charge: 0                    # In paise (0 = allow free upgrades)
  max-payment-attempts: 3
  payment-retry-interval: 1h               # Retry failed payments hourly
  grace-period-days: 5                     # Days before PAST_DUE → SUSPENDED
```

---

## Endpoints

### GET /api/v1/subscriptions/current

**Purpose:** Get current subscription state

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 123,
    "planId": 2,
    "planName": "PRO",
    "planDisplayName": "Professional Plan",
    "status": "ACTIVE",
    "billingCycle": "MONTHLY",
    "trialStart": null,
    "trialEnd": null,
    "trialDaysRemaining": null,
    "currentPeriodStart": "2026-04-15T00:00:00Z",
    "currentPeriodEnd": "2026-05-15T00:00:00Z",
    "priceMonthly": 5000.00,
    "priceAnnual": 50000.00,
    "currency": "INR",
    "cancelledAt": null,
    "cancelAtPeriodEnd": false,
    "upgradePending": false,
    "upgradePendingPlanName": null,
    "scheduledBillingCycle": null,
    "scheduledDowngradePlanId": null,
    "downgradeReason": null
  },
  "message": "Subscription fetched successfully"
}
```

**Security:**
- **Open to all authenticated users** (no BILLING_MANAGE required)
- Returns only current tenant's subscription

**UI Rendering:**

```javascript
// Status badges
if (sub.status === 'TRIALING') {
  return `Trial ends in ${sub.trialDaysRemaining} days`;
}
if (sub.status === 'ACTIVE') {
  return `Active • Next billing: ${sub.currentPeriodEnd}`;
}
if (sub.status === 'PAST_DUE') {
  return `⚠️ Payment overdue • ${sub.currentPeriodEnd} • Retry payment`;
}
if (sub.status === 'CANCELLED') {
  return `Cancelled • Expires: ${sub.currentPeriodEnd}`;
}

// Pending upgrade banner
if (sub.upgradePending) {
  return `📋 Pending: Upgrade to ${sub.upgradePendingPlanName} • Complete Payment`;
}

// Scheduled changes banner
if (sub.scheduledDowngradePlanId) {
  return `⬇️ Downgrading to FREE on ${sub.currentPeriodEnd}`;
}
if (sub.scheduledBillingCycle && sub.scheduledBillingCycle !== sub.billingCycle) {
  return `📅 Switching to ${sub.scheduledBillingCycle} on ${sub.currentPeriodEnd}`;
}
```

---

### GET /api/v1/subscriptions/upgrade/preview

**Purpose:** Preview upgrade cost before checkout

**Request:**
```
GET /api/v1/subscriptions/upgrade/preview?targetPlanId=3&billingCycle=ANNUAL
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "currentPlanId": 2,
    "currentPlanName": "PRO",
    "targetPlanId": 3,
    "targetPlanName": "ENTERPRISE",
    "currentBillingCycle": "MONTHLY",
    "targetBillingCycle": "ANNUAL",
    "fullCyclePrice": 120000.00,
    "currentPlanCreditDays": 5,
    "proratedCredit": 20000.00,
    "finalChargeAmount": 100000.00,
    "finalChargeInPaise": 10000000,
    "savingsVsMonthly": 12000.00,
    "newPeriodStart": "2026-05-14T14:30:00Z",
    "newPeriodEnd": "2027-05-14T14:30:00Z",
    "currency": "INR"
  },
  "message": "Upgrade preview fetched successfully"
}
```

**Calculation formula:**

```
fullCyclePrice = Enterprise annual price = 120,000 INR
currentPlanCredit = (remaining days in current period / cycle length) * monthly price
                 = (5 / 30) * 5,000 = 833.33
proratedCredit = currentPlanCredit = 833.33

finalCharge = fullCyclePrice - proratedCredit
            = 120,000 - 833.33 = 119,166.67
```

**No state change** — purely informational

**Error codes:**
- `400` — Invalid targetPlanId or billingCycle
- `403` — Cannot preview (e.g., already on target plan)
- `404` — Plan not found

---

### POST /api/v1/subscriptions/upgrade/pro

**Purpose:** Initiate upgrade to Pro plan

**Request:**
```json
{
  "billingCycle": "MONTHLY"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "orderId": "ord_2c7P8k4x9M3z5q",
    "razorpayOrderId": "ord_2c7P8k4x9M3z5q",
    "amount": 500000,
    "amountInPaise": 500000,
    "currency": "INR",
    "invoiceId": 456,
    "invoiceNumber": "INV-2026-0042",
    "keyId": "rzp_live_KEY_ABC123",
    "subscriptionId": 123,
    "planId": 2
  },
  "message": "Upgrade to Pro initiated. Complete payment to activate."
}
```

**Side effects:**
- Creates Invoice (status: OPEN, amount=500 INR)
- Creates Razorpay order
- Sets `Subscription.upgradePendingPlanId = 2` (PRO)
- Sets `Subscription.upgradePendingInvoiceId = 456`
- Sets `Subscription.upgradePendingRazorpayOrderId = ord_2c7P8k4x9M3z5q`
- Publishes `UpgradeInitiatedEvent`
- Does NOT change actual `planId` yet ✓

**Frontend: Open Razorpay Checkout**

```javascript
const options = {
  key: response.data.keyId,
  amount: response.data.amountInPaise,
  currency: response.data.currency,
  order_id: response.data.razorpayOrderId,
  handler: function(paymentResponse) {
    // Step 2: Verify payment
    axios.post('/api/v1/payments/verify', {
      razorpayOrderId: paymentResponse.razorpay_order_id,
      razorpayPaymentId: paymentResponse.razorpay_payment_id,
      razorpaySignature: paymentResponse.razorpay_signature
    })
  }
};
new Razorpay(options).open();
```

**Error codes:**
- `400` — Invalid billingCycle
- `403` — User lacks BILLING_MANAGE permission
- `409` — Upgrade already in progress (void or cancel first)
- `409` — Current subscription is PAST_DUE
- `500` — Razorpay API error

---

### POST /api/v1/subscriptions/upgrade/enterprise

**Purpose:** Initiate upgrade to Enterprise plan

**Request:**
```json
{
  "billingCycle": "ANNUAL"
}
```

**Response:** Same structure as `/upgrade/pro`

**Notable:** Enterprise pricing is typically higher; ANNUAL billing applies heavy discount vs MONTHLY.

---

### POST /api/v1/subscriptions/downgrade/free

**Purpose:** Downgrade to Free plan

**Request:**
```json
{
  "atPeriodEnd": true,
  "reason": "Too expensive for current use"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 123,
    "planId": 1,
    "planName": "FREE",
    "status": "ACTIVE",
    "billingCycle": "MONTHLY",
    "currentPeriodStart": "2026-04-15T00:00:00Z",
    "currentPeriodEnd": "2026-05-15T00:00:00Z",
    "cancelAtPeriodEnd": false,
    "scheduledDowngradePlanId": 1,
    "downgradeReason": "Too expensive for current use",
    "message": "Downgrade to Free scheduled at end of current billing period."
  },
  "message": "Downgrade to Free scheduled at end of current billing period."
}
```

**Behaviors:**

| `atPeriodEnd` | Effect | Billing | Features |
|---------------|--------|---------|----------|
| `true` | FREE takes effect at period end | No new invoice | Retain Pro features until period end |
| `false` | FREE takes effect immediately | No invoice, no refund | Pro features disabled immediately |

**Side effects:**
- If `atPeriodEnd=true`:
  - Sets `Subscription.scheduledDowngradePlanId = 1`
  - Sets `Subscription.downgradeReason = "..."`
  - Subscription still ACTIVE (Pro features until period end)
- If `atPeriodEnd=false`:
  - Sets `Subscription.planId = 1` (FREE)
  - Voids any pending upgrade invoice
  - Frontend likely disables Pro features immediately
- Publishes `DowngradeInitiatedEvent`

**Error codes:**
- `400` — `atPeriodEnd` missing
- `403` — User lacks BILLING_MANAGE permission
- `409` — Already on Free plan

---

### POST /api/v1/subscriptions/cycle

**Purpose:** Change billing frequency (MONTHLY ↔ ANNUAL)

**Request:**
```json
{
  "newBillingCycle": "ANNUAL"
}
```

**Response (200 OK) — MONTHLY to ANNUAL:**
```json
{
  "success": true,
  "data": {
    "orderId": "ord_3xK9p2m1q5r",
    "razorpayOrderId": "ord_3xK9p2m1q5r",
    "amount": 48000,
    "amountInPaise": 4800000,
    "currency": "INR",
    "invoiceId": 457,
    "keyId": "rzp_live_KEY_ABC123"
  },
  "message": "Upgrade to ANNUAL initiated. Complete payment to activate."
}
```

**Response (200 OK) — ANNUAL to MONTHLY:**
```json
{
  "success": true,
  "data": {
    "id": 123,
    "planId": 2,
    "planName": "PRO",
    "billingCycle": "MONTHLY",
    "scheduledBillingCycle": "MONTHLY",
    "currentPeriodEnd": "2027-05-15T00:00:00Z",
    "message": "Switching to MONTHLY at end of current annual period."
  },
  "message": "Cycle change scheduled for period end."
}
```

**Behaviors:**

| Transition | Cost | Timing | Status |
|-----------|------|--------|--------|
| MONTHLY → ANNUAL | Additional charge (annual discount - monthly coverage) | Immediate (payment required) | Returns SubscriptionOrderResponse |
| ANNUAL → MONTHLY | No cost | Takes effect at next renewal | Returns SubscriptionResponse with `scheduledBillingCycle` |

**Logic:**

```
MONTHLY → ANNUAL:
  charge = (annual_price * days_left_in_month / 365) - (monthly_price * days_left_in_month / 30)
  
ANNUAL → MONTHLY:
  No immediate cost; scheduled for next renewal
```

---

### POST /api/v1/subscriptions/cancel

**Purpose:** Cancel subscription (stop renewals)

**Request:**
```json
{
  "reason": "Not needed anymore"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 123,
    "status": "CANCELLED",
    "cancelledAt": "2026-05-14T14:30:00Z",
    "cancelAtPeriodEnd": true,
    "currentPeriodEnd": "2026-05-15T00:00:00Z",
    "message": "Subscription cancelled. Service will end on 2026-05-15."
  },
  "message": "Subscription cancelled successfully"
}
```

**Side effects:**
- Sets `Subscription.status = CANCELLED`
- Sets `Subscription.cancelledAt = NOW()`
- Sets `Subscription.cancelAtPeriodEnd = true`
- Service remains active until period end (no immediate cutoff)
- BillingCycleJob will mark as EXPIRED when period ends
- Publishes `SubscriptionCancelledEvent`

---

### POST /api/v1/subscriptions/reactivate

**Purpose:** Reactivate a cancelled subscription before it expires

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 123,
    "status": "ACTIVE",
    "cancelledAt": null,
    "cancelAtPeriodEnd": false,
    "currentPeriodEnd": "2026-05-15T00:00:00Z",
    "message": "Subscription reactivated."
  },
  "message": "Subscription reactivated successfully"
}
```

**Preconditions:**
- Current `status == CANCELLED` (not EXPIRED)
- `currentPeriodEnd > NOW()` (before expiry)

---

### GET /api/v1/invoices

**Purpose:** List all invoices

**Request:**
```
GET /api/v1/invoices?page=0&size=20&sort=createdAt,desc
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 456,
        "invoiceNumber": "INV-2026-0042",
        "subscriptionId": 123,
        "status": "PAID",
        "subtotal": 5000.00,
        "taxAmount": 900.00,
        "discountAmount": 0,
        "totalAmount": 5900.00,
        "currency": "INR",
        "dueDate": "2026-06-14T00:00:00Z",
        "paidAt": "2026-05-14T14:35:00Z",
        "billingPeriodStart": "2026-04-15T00:00:00Z",
        "billingPeriodEnd": "2026-05-15T00:00:00Z",
        "pdfUrl": null,
        "lineItems": [
          {
            "id": 1001,
            "description": "Professional Plan - Monthly",
            "quantity": 1,
            "unitPrice": 5000.00,
            "totalPrice": 5000.00,
            "lineItemType": "PLAN"
          }
        ],
        "createdAt": "2026-04-15T00:00:00Z"
      }
    ],
    "totalElements": 42,
    "totalPages": 3,
    "currentPage": 0,
    "pageSize": 20
  },
  "message": "Invoices fetched successfully"
}
```

**Filtering & sorting:**
- Sorted by `createdAt` DESC by default
- Pagination: page=0 (first page), size=20 (items per page)

**Security:** Requires `BILLING_MANAGE` permission

---

### GET /api/v1/invoices/{id}

**Purpose:** Get single invoice with full details

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 456,
    "invoiceNumber": "INV-2026-0042",
    "subscriptionId": 123,
    "status": "PAID",
    "subtotal": 5000.00,
    "taxAmount": 900.00,
    "discountAmount": 0,
    "totalAmount": 5900.00,
    "currency": "INR",
    "dueDate": "2026-06-14T00:00:00Z",
    "paidAt": "2026-05-14T14:35:00Z",
    "billingPeriodStart": "2026-04-15T00:00:00Z",
    "billingPeriodEnd": "2026-05-15T00:00:00Z",
    "pdfUrl": "https://s3.amazonaws.com/invoices/INV-2026-0042.pdf?X-Amz-Expires=3600",
    "razorpayInvoiceId": "inv_2c7P8k4x9M3z5q",
    "lineItems": [
      {
        "id": 1001,
        "description": "Professional Plan - Monthly",
        "quantity": 1,
        "unitPrice": 5000.00,
        "totalPrice": 5000.00,
        "lineItemType": "PLAN"
      },
      {
        "id": 1002,
        "description": "18% GST",
        "quantity": 1,
        "unitPrice": 900.00,
        "totalPrice": 900.00,
        "lineItemType": "TAX"
      }
    ],
    "createdAt": "2026-04-15T00:00:00Z",
    "updatedAt": "2026-05-14T14:35:00Z"
  },
  "message": "Invoice fetched successfully"
}
```

---

### GET /api/v1/invoices/{id}/download

**Purpose:** Download invoice as PDF

**Request:**
```
GET /api/v1/invoices/456/download
```

**Response (200 OK):**
```
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="INV-2026-0042.pdf"
Content-Length: 45823

[PDF binary data]
```

**Behavior:**
- Generates PDF using Thymeleaf template + Apache FOP
- Pre-signed S3 URL expires in 1 hour
- Direct download to browser

---

### POST /api/v1/invoices/{id}/void

**Purpose:** Void an invoice (mark as cancelled)

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 456,
    "status": "VOID",
    "message": "Invoice voided successfully"
  },
  "message": "Invoice voided successfully"
}
```

**Preconditions:**
- Invoice status is DRAFT or OPEN (not PAID, not already VOID)

**Side effects:**
- Sets `Invoice.status = VOID`
- Records in `AuditLog`
- Associated `Payment` records remain (for audit trail)

**Error codes:**
- `400` — Cannot void PAID invoice (use refund instead)
- `400` — Invoice already VOID
- `404` — Invoice not found

---

### POST /api/v1/payments/process/{invoiceId}

**Purpose:** Initiate payment for an invoice

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "razorpayOrderId": "ord_2c7P8k4x9M3z5q",
    "amount": 500000,
    "amountInPaise": 500000,
    "currency": "INR",
    "keyId": "rzp_live_KEY_ABC123",
    "invoiceId": 456,
    "invoiceNumber": "INV-2026-0042"
  },
  "message": "Payment order created successfully"
}
```

**Idempotency:**
- Key: `pay-{invoiceId}`
- Calling twice returns same `razorpayOrderId`
- No duplicate orders created

**Error codes:**
- `404` — Invoice not found
- `400` — Invoice amount is 0 (no payment needed)
- `409` — Invoice already PAID
- `500` — Razorpay API error

---

### POST /api/v1/payments/verify

**Purpose:** Verify Razorpay payment and capture funds

**Request:**
```json
{
  "razorpayOrderId": "ord_2c7P8k4x9M3z5q",
  "razorpayPaymentId": "pay_2c7P8k4x9M3z5q",
  "razorpaySignature": "9ef4dffbfd84f1318f6739a3ce19f9d85851857ae648f114332d8401e0949a3d"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 789,
    "invoiceId": 456,
    "status": "SUCCEEDED",
    "amount": 500000,
    "amountInPaise": 500000,
    "currency": "INR",
    "razorpayOrderId": "ord_2c7P8k4x9M3z5q",
    "razorpayPaymentId": "pay_2c7P8k4x9M3z5q",
    "razorpaySignature": "9ef4dffbfd84f1318f6739a3ce19f9d85851857ae648f114332d8401e0949a3d",
    "paidAt": "2026-05-14T14:35:00Z"
  },
  "message": "Payment verified and captured successfully"
}
```

**Verification steps:**
1. Extract signature from request
2. Construct message: `razorpayOrderId|razorpayPaymentId`
3. HMAC-SHA256 with Razorpay secret
4. Compare to provided signature (constant-time)
5. If match: mark Payment SUCCEEDED, mark Invoice PAID
6. If upgrade in progress: call `SubscriptionService.activateUpgradeAfterPayment()`

**Side effects:**
- Sets `Payment.status = SUCCEEDED`
- Sets `Payment.paidAt = NOW()`
- Sets `Invoice.status = PAID`
- If pending upgrade: activates new plan
- Publishes `PaymentSucceededEvent`

**Error codes:**
- `400` — Signature verification failed (invalid payment)
- `400` — Missing razorpayOrderId, razorpayPaymentId, or razorpaySignature
- `400` — Payment amount mismatch
- `404` — Order not found in Razorpay

---

### POST /api/v1/payments/{id}/retry

**Purpose:** Retry a failed payment

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "razorpayOrderId": "ord_3xK9p2m1q5r",
    "amount": 500000,
    "amountInPaise": 500000,
    "currency": "INR",
    "keyId": "rzp_live_KEY_ABC123",
    "invoiceId": 456
  },
  "message": "Retry order created successfully"
}
```

**Preconditions:**
- Previous payment status is FAILED
- `retryCount < 3` (max 3 attempts)

**Side effects:**
- Increments `Payment.retryCount`
- Creates new Razorpay order
- Sets `Payment.status = PENDING`

**Error codes:**
- `400` — Payment not in FAILED state
- `400` — Max retry attempts exceeded (3 failures = suspend subscription)
- `404` — Payment not found

---

### POST /api/v1/payments/{id}/refund

**Purpose:** Refund a successful payment

**Request:**
```json
{
  "amount": 100000
}
```

Or for full refund:
```json
{
  "amount": 0
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 789,
    "invoiceId": 456,
    "status": "REFUNDED",
    "amount": 500000,
    "refundAmount": 100000,
    "refundId": "rfnd_2c7P8k4x9M3z5q",
    "refundedAt": "2026-05-14T14:40:00Z"
  },
  "message": "Refund initiated successfully"
}
```

**Behavior:**
- `amount=0` or omitted → full refund
- `amount>0` → partial refund (≤ original payment amount)
- Razorpay processes asynchronously; status updates via webhook

**Error codes:**
- `400` — Payment not in SUCCEEDED state
- `400` — Refund amount exceeds original payment
- `404` — Payment not found

---

### GET /api/v1/usage/limits

**Purpose:** Get real-time usage vs plan limits

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "limits": [
      {
        "metric": "API_CALLS",
        "current": 8500,
        "limit": 10000,
        "remaining": 1500,
        "percentUsed": 85.0,
        "periodStart": "2026-05-01T00:00:00Z",
        "periodEnd": "2026-05-31T23:59:59Z"
      },
      {
        "metric": "ACTIVE_USERS",
        "current": 12,
        "limit": 20,
        "remaining": 8,
        "percentUsed": 60.0,
        "periodStart": "2026-05-01T00:00:00Z",
        "periodEnd": "2026-05-31T23:59:59Z"
      },
      {
        "metric": "STORAGE_GB",
        "current": 4.2,
        "limit": 10,
        "remaining": 5.8,
        "percentUsed": 42.0,
        "periodStart": "2026-05-01T00:00:00Z",
        "periodEnd": "2026-05-31T23:59:59Z"
      }
    ]
  },
  "message": "Usage limits fetched successfully"
}
```

**Calculations:**
- `remaining = max(limit - current, 0)`
- `percentUsed = (current / limit) * 100`
- `null` values indicate no limit (unlimited plan)

**Security:** Open to all authenticated users

---

### GET /api/v1/usage?start=...&end=...

**Purpose:** Get usage for a specific period

**Request:**
```
GET /api/v1/usage?start=2026-05-01T00:00:00Z&end=2026-05-31T23:59:59Z
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "metric": "API_CALLS",
      "current": 8500,
      "limit": 10000,
      "remaining": 1500,
      "percentUsed": 85.0,
      "periodStart": "2026-05-01T00:00:00Z",
      "periodEnd": "2026-05-31T23:59:59Z"
    },
    {
      "metric": "ACTIVE_USERS",
      "current": 12,
      "limit": 20,
      "remaining": 8,
      "percentUsed": 60.0,
      "periodStart": "2026-05-01T00:00:00Z",
      "periodEnd": "2026-05-31T23:59:59Z"
    }
  ],
  "message": "Usage for period fetched successfully"
}
```

**Validation:**
- `start` must be before `end` (400 if not)
- Both dates required (ISO-8601 format)

---

### GET /api/v1/dashboard/billing

**Purpose:** Get billing dashboard overview

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "currentPlan": {
      "planName": "PRO",
      "billingCycle": "MONTHLY",
      "price": 5000.00,
      "renewalDate": "2026-05-15T00:00:00Z"
    },
    "nextPayment": {
      "amount": 5900.00,
      "dueDate": "2026-05-15T00:00:00Z",
      "status": "UPCOMING"
    },
    "usage": {
      "apiCalls": { "current": 8500, "limit": 10000, "percent": 85 },
      "activeUsers": { "current": 12, "limit": 20, "percent": 60 },
      "storage": { "current": 4.2, "limit": 10, "percent": 42 }
    },
    "upcomingEvents": [
      {
        "date": "2026-05-15T00:00:00Z",
        "event": "Billing cycle renewal",
        "amount": 5900.00
      }
    ],
    "invoiceHistory": [
      {
        "invoiceNumber": "INV-2026-0042",
        "amount": 5900.00,
        "date": "2026-04-15T00:00:00Z",
        "status": "PAID"
      }
    ],
    "usageTrend": [
      { "date": "2026-05-08", "apiCalls": 1200 },
      { "date": "2026-05-09", "apiCalls": 1100 },
      { "date": "2026-05-10", "apiCalls": 1500 }
    ]
  },
  "message": "Billing dashboard fetched successfully"
}
```

**Parameters:**
- `days` — Usage trend lookback (1-30, default 7)

---

## Error Handling

See [error-handling.md](./error-handling.md#billing-error-codes) for complete error list.

**Common errors:**

| Error Code | HTTP | Message | Cause |
|------------|------|---------|-------|
| `BILL_001` | 400 | Invalid subscription state | Cannot perform action on this subscription status |
| `BILL_002` | 409 | Upgrade already in progress | Void or cancel pending upgrade first |
| `BILL_003` | 403 | Cannot upgrade, subscription PAST_DUE | Pay overdue invoice first |
| `BILL_004` | 400 | Invalid payment signature | Razorpay signature verification failed |
| `BILL_005` | 409 | Invoice already paid | Cannot process payment for paid invoice |
| `BILL_006` | 400 | Max payment retries exceeded | Subscription will be suspended |
| `BILL_007` | 404 | Invoice not found | Invoice ID doesn't exist in this tenant |
| `BILL_008` | 400 | Cannot downgrade to current plan | Already on target plan |

---

## State Machines

### Subscription States

```
                    ┌─ TRIALING ─┐
                    │             │
                    ▼             ▼
┌─ ACTIVE ◄─ [activate()]  [trial ends]
│   ▲
│   │ [upgrade/cycle→MONTHLY]
│   │
│   ▼
├─ PAST_DUE ◄─ [no payment]
│   │
│   ├─ [max retries exceeded]
│   │   ▼
│   ├─ CANCELLED ◄─ [user cancels]
│   │   │
│   │   ├─ [reactivate()]
│   │   │   ▼
│   │   │ ACTIVE (restored)
│   │   │
│   │   └─ [period end]
│   │       ▼
│   └─ EXPIRED
│
└─ CANCELLED ◄─ [user cancels]
    │
    ├─ [reactivate() before expiry]
    │   ▼
    │ ACTIVE (restored)
    │
    └─ [period end]
        ▼
        EXPIRED
```

### Invoice States

```
DRAFT ─┐
       │ [payment processed]
       ▼
OPEN ──┤
       │ [payment verified]
       ├─► PAID
       │
       └─► VOID ◄─ [user voids]
```

### Payment States

```
PENDING ─┐
         ├─► SUCCEEDED ◄─ [signature verified]
         │
         ├─► FAILED ◄─ [error from Razorpay]
         │   │
         │   ├─ [retry]
         │   │   ▼
         │   ├─ PENDING (new attempt)
         │   │
         │   └─ [max retries]
         │       ▼
         │       SUSPENDED (subscription)
         │
         └─► REFUNDED ◄─ [refund processed]
```

---

## Integration Patterns

### Frontend: React + TanStack Query

```typescript
// 1. Query current subscription
const useSubscription = () => {
  return useQuery({
    queryKey: ['subscription'],
    queryFn: () => apiClient.get('/api/v1/subscriptions/current')
  });
};

// 2. Preview upgrade
const useUpgradePreview = (targetPlanId: number, cycle: BillingCycle) => {
  return useQuery({
    queryKey: ['upgradePreview', targetPlanId, cycle],
    queryFn: () => apiClient.get('/api/v1/subscriptions/upgrade/preview', {
      params: { targetPlanId, billingCycle: cycle }
    })
  });
};

// 3. Initiate upgrade
const useInitiateUpgrade = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (cycle: BillingCycle) => 
      apiClient.post('/api/v1/subscriptions/upgrade/pro', { billingCycle: cycle }),
    onSuccess: (response) => {
      // Open Razorpay
      const options = {
        key: response.data.keyId,
        amount: response.data.amountInPaise,
        currency: response.data.currency,
        order_id: response.data.razorpayOrderId,
        handler: (paymentResponse) => {
          // Verify payment
          apiClient.post('/api/v1/payments/verify', {
            razorpayOrderId: paymentResponse.razorpay_order_id,
            razorpayPaymentId: paymentResponse.razorpay_payment_id,
            razorpaySignature: paymentResponse.razorpay_signature
          }).then(() => {
            // Invalidate subscription query
            queryClient.invalidateQueries({ queryKey: ['subscription'] });
            showSuccess('Upgrade successful!');
          });
        }
      };
      new Razorpay(options).open();
    }
  });
};
```

### Backend: Service Layer

```java
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {
    
    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    
    public SubscriptionOrderResponse initiateUpgradeToPro(UpgradeRequest request) {
        // 1. Validate current subscription
        Subscription current = getActiveSubscription();
        if (current.hasUpgradePending()) {
            throw SubscriptionException.upgradeInProgress();
        }
        
        // 2. Calculate proration
        BigDecimal prorationCredit = calculateProration(current);
        Plan targetPlan = planRepository.findByName("PRO");
        BigDecimal chargeAmount = (request.getBillingCycle() == BillingCycle.MONTHLY
            ? targetPlan.getPriceMonthly()
            : targetPlan.getPriceAnnual()).subtract(prorationCredit);
        
        // 3. Create invoice
        Invoice invoice = invoiceService.createUpgradeInvoice(current, targetPlan, chargeAmount);
        
        // 4. Create Razorpay order
        OrderResponse razorpayOrder = paymentService.createOrder(
            invoice.getId(),
            chargeAmount.multiply(BigDecimal.valueOf(100)).longValue()  // Convert to paise
        );
        
        // 5. Mark as pending (but DON'T change planId yet)
        current.setUpgradePendingPlanId(targetPlan.getId());
        current.setUpgradePendingInvoiceId(invoice.getId());
        current.setUpgradePendingRazorpayOrderId(razorpayOrder.getRazorpayOrderId());
        subscriptionRepository.save(current);
        
        // 6. Publish event
        applicationEventPublisher.publishEvent(
            new UpgradeInitiatedEvent(current, targetPlan));
        
        return SubscriptionOrderResponse.from(razorpayOrder, invoice);
    }
    
    public void activateUpgradeAfterPayment(Long subscriptionId, Long newPlanId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
            .orElseThrow();
        
        // CRITICAL: Only called after payment verified
        sub.setPlanId(newPlanId);
        sub.setCurrentPeriodStart(Instant.now());
        sub.setCurrentPeriodEnd(Instant.now().plus(Duration.ofDays(365)));
        sub.clearPendingUpgrade();
        subscriptionRepository.save(sub);
        
        applicationEventPublisher.publishEvent(
            new UpgradeActivatedEvent(sub, newPlanId));
    }
}
```

---

## Summary

| Feature | Mechanism | Safety |
|---------|-----------|--------|
| 2-step upgrades | planId changed after payment | Prevents paid upgrades |
| Proration | Days-left calculation | Fair pricing on mid-cycle changes |
| Scheduled changes | Queued in separate fields | No immediate cost |
| Payment retry | Exponential backoff, 3 attempts | Automatic recovery |
| Signature verification | HMAC-SHA256 | Prevents forgery |
| Idempotency | Order deduplication | Safe retries |

