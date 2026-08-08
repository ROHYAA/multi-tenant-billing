---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - billing
  - proration
  - upgrade
  - downgrade
  - math
  - credit-calculation
  - edge-cases
  - pricing
related_documents:
  - ./subscription-lifecycle.md
  - ./plan-change-flow.md
  - ./payment-processing.md
  - ../05-platform-billing/invoice.md
---

# Proration: Upgrade Credit Calculation

## Executive Summary

**Proration** is the mechanism to charge fairly when a user upgrades or downgrades mid-billing-cycle. If a tenant is on a MONTHLY ₹299 plan with 10 days left and upgrades to ANNUAL ₹2999 plan, they should not pay the full ₹2999—they deserve a credit for the unused ₹99.67 (10/30 × ₹299). This document explains the proration model, formulas, edge cases, Razorpay minimum handling, and why certain subscriptions (TRIALING, FREE) have no credit. It includes worked examples and integration points.

---

## Context / Problem

### Why Proration Matters

**Scenario: User upgrades mid-cycle without proration**
```
Current: MONTHLY ₹299, 20 days left in period
Upgrade to: ANNUAL ₹2999

Bad (no credit):  User pays full ₹2999 → feels robbed ($35.83 USD)
Good (with credit): User pays ₹2999 - ₹199.33 = ₹2799.67 → fair

Credit: (20 / 30) × ₹299 = ₹199.33
```

**Scenario: User downgrades mid-cycle**
```
Current: ANNUAL ₹2999, 200 days left
Downgrade to: MONTHLY ₹299

Policy: No refund (sunk cost, prevents abuse)
User continues on PRO until period end, then gets FREE next cycle.
Next invoice: ₹0
```

### Historical Problem: Manual Calculation Errors

Without a systematic proration service, different code paths calculated credit differently:
- One endpoint: `(remaining / 30) × price` (simplified)
- Another endpoint: `(remaining / daysInMonth) × price` (varies)
- No rounding consistency → ₹100.0 vs ₹100.01 inconsistencies
- No Razorpay minimum handling → failed to create orders for tiny amounts

**Solution: ProrationService** — single source of truth for all proration math.

---

## Dependencies

### Inbound (What calls this)
- **SubscriptionController** → `GET /upgrade/preview` — show user the charge before committing
- **SubscriptionService** → `initiateUpgrade*()` — calculate charge for Razorpay order
- **BillingCycleChangeController** → `POST /cycle` — calculate cost of cycle change

### Outbound (What this calls)
- **PlanService** → `getPlanById()`, `getPriceMonthly()`, `getPriceAnnual()` — resolve plan prices
- **Subscription entity** → read `planId`, `status`, `billingCycle`, `currentPeriodEnd`
- **BigDecimal** → all calculations use Big Decimal for precision (prevent floating-point rounding errors)

### Configuration
- `app.billing.cycle.monthly-days` — Default 30 (fixed month for consistency, not calendar month)
- `app.billing.cycle.annual-days` — Default 365 (fixed year, not accounting for leap years)
- Razorpay minimum: ₹1 (hardcoded, per Razorpay API)

---

## Design / Implementation

### Proration Model: Who Gets Credit?

| From Plan | Status | Target Plan | Credit? | Reason |
|-----------|--------|-------------|---------|--------|
| FREE | ACTIVE | Any paid | NO | FREE costs nothing |
| TRIALING | TRIALING | Any paid | NO | Trial has no monetary value |
| Paid | ACTIVE | Higher paid | YES | Unused days on current plan |
| Paid | ACTIVE | Lower paid | NO | Downgrade, no refund policy |
| Paid | ACTIVE | FREE | NO | Downgrade, no refund |
| PAST_DUE | PAST_DUE | Any paid | NO | Delinquent tenants cannot change |
| EXPIRED | EXPIRED | Any paid | NO | Subscription ended |
| CANCELLED | CANCELLED | Any paid | NO | Subscription cancelled |

**Key insight:** Only ACTIVE subscriptions on PAID plans get credit.

### Proration Formula

```
remainingDays = ChronoUnit.DAYS.between(now, subscription.currentPeriodEnd)
totalDays = (subscription.billingCycle == MONTHLY) ? 30 : 365

dailyRate = currentPlanCyclePrice / totalDays
credit = dailyRate × remainingDays
         (rounded HALF_UP to 2 decimal places)

chargeAmount = targetPlanCyclePrice - credit
               (minimum 0 — never negative)
```

### Worked Example 1: MONTHLY → ANNUAL Upgrade

**Setup:**
```
Current: MONTHLY ₹299, created 2026-05-17, period ends 2026-06-16 (30 days)
Today: 2026-05-27 (10 days passed, 20 days remaining)
Target: ANNUAL ₹2999, MONTHLY billing cycle requested
```

**Calculation:**
```
remainingDays = 20
totalDays = 30
dailyRate = 299 / 30 = ₹9.9667 per day (6 decimal places)
credit = 9.9667 × 20 = ₹199.33 (rounded HALF_UP)

targetPlanCyclePrice = 2999
chargeAmount = 2999 - 199.33 = ₹2799.67

Preview returns:
{
  "fullCyclePrice": 2999.00,
  "creditAmount": 199.33,
  "remainingDays": 20,
  "totalPeriodDays": 30,
  "chargeAmount": 2799.67,
  "chargeAmountPaise": 279967,  // for Razorpay
  "noPaymentRequired": false,
  "newPeriodStart": "2026-05-27T14:30:00Z",
  "newPeriodEnd": "2026-05-26T14:30:00Z" (1 year later)
}
```

### Worked Example 2: Cycle Change MONTHLY → ANNUAL (Same Plan)

**Setup:**
```
Current: PRO MONTHLY ₹299, 20 days left
Target: PRO ANNUAL ₹2999 (not changing plan, just cycle)
```

**Calculation:**
```
Credit calculation:
remainingDays = 20
dailyRate = 299 / 30 = ₹9.9667
credit = 9.9667 × 20 = ₹199.33

chargeAmount = 2999 - 199.33 = ₹2799.67
```

Same as Example 1 (user gets credit for unused MONTHLY period when switching to ANNUAL).

### Worked Example 3: FREE Plan (No Credit)

**Setup:**
```
Current: FREE (obviously no cost, status = ACTIVE)
Target: MONTHLY ₹299, 25 days left (irrelevant)
```

**Calculation:**
```
Since currentPlan is FREE:
calculateCredit() returns BigDecimal.ZERO immediately

chargeAmount = 299 - 0 = ₹299.00
```

### Worked Example 4: TRIALING Subscription (No Credit)

**Setup:**
```
Current: TRIALING status, planId = 1 (FREE plan during trial)
         Trial ends 2026-06-16, today is 2026-05-27 (20 days left)
Target: MONTHLY ₹299
```

**Calculation:**
```
Status check in calculateCredit():
if (subscription.getStatus() == SubscriptionStatus.TRIALING) {
    return BigDecimal.ZERO;
}

chargeAmount = 299 - 0 = ₹299.00
```

Trial has no monetary value, even though it's "active" in timeline. No credit.

### Worked Example 5: Downgrade ANNUAL → MONTHLY (No Refund)

**Setup:**
```
Current: ANNUAL ₹2999, 200 days left
Target: MONTHLY ₹299
```

**Calculation:**
```
This is a downgrade (ANNUAL price > MONTHLY price).
ProrationService treats it as a scheduled downgrade (via SubscriptionService logic).
No Razorpay order created, no payment.

If atPeriodEnd=false (immediate downgrade):
chargeAmount = ₹0, user gets FREE immediately.
Days paid on ANNUAL are sunk (no refund).

If atPeriodEnd=true (scheduled downgrade):
User keeps ANNUAL until 2026-12-14, then FREE next period.
```

---

## Edge Cases & Rounding

### Edge Case 1: Razorpay Minimum (Last Day of Period)

**Setup:**
```
Current: MONTHLY ₹299, 1 day left in period
Target: PRO MONTHLY ₹399
```

**Calculation:**
```
remainingDays = 1
dailyRate = 299 / 30 = ₹9.9667
credit = 9.9667 × 1 = ₹9.97 (rounded)

chargeAmount = 399 - 9.97 = ₹389.03

chargeAmountPaise = floor(389.03 × 100) = 38903
```

✅ No issue — ₹389.03 is well above Razorpay minimum of ₹1.

**But if:**
```
Current: MONTHLY ₹500, 1 day left
Target: FREE

chargeAmount = 0 - (500/30) = ₹16.67 credit (but free plan, so credit irrelevant)
chargeAmountPaise = 0  (downgrade, no payment required)
```

✅ No order created for FREE.

**Edge case: Tiny charge:**
```
Current: MONTHLY ₹1.50, 1 day left
Target: ANNUAL ₹50

remainingDays = 1
credit = (1.50 / 30) × 1 = ₹0.05

chargeAmount = 50 - 0.05 = ₹49.95
chargeAmountPaise = 4995
```

✅ No Razorpay minimum needed (₹49.95 > ₹1).

**But if:**
```
Current: MONTHLY ₹0.50, 1 day left
Target: ANNUAL ₹50

credit = (0.50 / 30) × 1 = ₹0.0167
chargeAmount = 50 - 0.0167 = ₹49.9833 ≈ ₹49.98

chargeAmountPaise = 4998
```

✅ Still > ₹1.

**Actual edge case (rare):**
```
Current: MONTHLY ₹30, 1 day left
Target: MONTHLY ₹31

credit = (30 / 30) × 1 = ₹1.00
chargeAmount = 31 - 1.00 = ₹30.00
```

✅ Still > ₹1.

**Theoretical worst case (never seen in practice):**
```
If ProrationService somehow computed charge = ₹0.50:
applyRazorpayMinimum(BigDecimal.valueOf(0.50))
→ 0.50 < 1.0? Yes
→ Return 1.0  (round up to ₹1)

chargeAmountPaise = 100  (₹1 in paise)
```

✅ Handled by `applyRazorpayMinimum()`.

### Edge Case 2: Rounding Precision

All calculations use **HALF_UP** rounding at 6 decimal places (intermediate) and 2 decimal places (final currency):

```java
BigDecimal dailyRate = currentCyclePrice.divide(
    BigDecimal.valueOf(totalDays), 6, RoundingMode.HALF_UP);
    
BigDecimal credit = dailyRate.multiply(BigDecimal.valueOf(remaining))
    .setScale(2, RoundingMode.HALF_UP);
```

**Why HALF_UP?**
- Rounds ₹100.5 → ₹101 (fair to company, standard accounting)
- Symmetric: ₹100.4 → ₹100, ₹100.5 → ₹101

**Why 6 decimal places for dailyRate?**
- Prevents rounding errors accumulating in intermediate calculations
- Final result is rounded to 2dp for display/billing

**Example:**
```
Price = ₹299, Days = 30
dailyRate = 299 / 30 = 9.966666... (not truncated)
Stored as: 9.966667 (6dp, HALF_UP)

If only 2dp: dailyRate = 9.97
charge (20 days) = 9.97 × 20 = 199.40  (ERROR: should be 199.33)

With 6dp: dailyRate = 9.966667
charge (20 days) = 9.966667 × 20 = 199.33333 → 199.33 (2dp)  (CORRECT)
```

### Edge Case 3: Period Length Variations (MONTHLY = 30 Fixed)

**Problem:** February has 28 days, July has 31 — should we account for actual days?

**MTBS Decision:** Use FIXED 30 days for MONTHLY, 365 for ANNUAL.

**Why?**
- Consistency: Same billing date always means same price per day
- Simplicity: Users understand fixed months
- Avoid leap year complications
- Industry standard for SaaS

**Consequence:**
```
Feb subscription created 2026-02-15, 28 days in Feb
User perceives: "I get 28 days of Feb, not 30" (edge case complaint)
System: "You get ₹299 for 30 days worth, period"
```

Fair trade-off. Never heard customer complaint about this.

---

## Code Flow

### GET /api/subscriptions/upgrade/preview

```
1. Frontend calls: GET /upgrade/preview?targetPlanId=2&billingCycle=MONTHLY

2. SubscriptionController.previewUpgrade()
   ├─ SubscriptionService.previewUpgrade(targetPlanId, cycle)
   │
   ├─ ProrationService.buildPreview(current, targetPlanId, cycle)
   │  ├─ Load current plan + target plan
   │  ├─ Validate upgrade allowed
   │  ├─ Calculate fullCyclePrice = resolveFullCyclePrice(targetPlan, cycle)
   │  ├─ Calculate credit = calculateCredit(current, currentPlan, cycle)
   │  ├─ Calculate chargeAmount = max(fullCyclePrice - credit, 0)
   │  ├─ Apply Razorpay minimum: if 0 < chargeAmount < ₹1, set to ₹1
   │  ├─ Convert to paise: chargeAmountPaise = chargeAmount × 100
   │  └─ Return UpgradePreviewResponse
   │
   └─ Return 200 OK with preview data (no state change)
```

### POST /api/subscriptions/upgrade/pro → Step 1

```
1. SubscriptionService.initiateUpgradeToPro(request)
   ├─ Load target plan
   ├─ Load current subscription
   ├─ Validate not already upgrading
   ├─ Call previewUpgrade() to get charge amount
   ├─ InvoiceService.createInvoiceForUpgrade()
   │  ├─ ProrationService.calculateChargeAmount()
   │  │  ├─ Resolve full cycle price
   │  │  ├─ Calculate credit
   │  │  ├─ Return charge = price - credit (min 0)
   │  │  └─ Apply Razorpay minimum
   │  ├─ Create OPEN invoice with chargeAmount
   │  └─ Return invoice
   │
   ├─ PaymentService.processPayment(invoiceId)
   │  ├─ Create Razorpay order with amount (in paise)
   │  └─ Return OrderResponse
   │
   ├─ Set subscription.upgradePendingInvoiceId
   ├─ Set subscription.upgradePendingPlanId
   ├─ Save subscription
   └─ Return SubscriptionOrderResponse (razorpayOrderId, keyId, amount)
```

**Key:** ProrationService calculates charge, PaymentService creates order with that charge, SubscriptionService records pending state.

---

## Code References

| Class | Method | Purpose |
|-------|--------|---------|
| `ProrationService` | `buildPreview()` | Full preview: credit, charge, new period, no DB write |
| `ProrationService` | `calculateChargeAmount()` | Pure calculation: returns charge in INR |
| `ProrationService` | `calculateCredit()` | Private: compute credit for current plan unused portion |
| `ProrationService` | `resolveFullCyclePrice()` | Private: load plan price for cycle |
| `ProrationService` | `toPaise()` | Convert INR to paise for Razorpay |
| `ProrationService` | `remainingDays()` | Days left in current billing period |
| `ProrationService` | `applyRazorpayMinimum()` | Round up tiny charges to ₹1 if needed |
| `ProrationService` | `validateUpgradeTarget()` | Block invalid plan combinations |
| `SubscriptionService` | `previewUpgrade()` | Facade: call ProrationService, return to frontend |
| `SubscriptionService` | `initiateUpgrade*()` | Step 1: call ProrationService.calculateChargeAmount() for order |

---

## Rules / Constraints

1. **Proration Credit Only for ACTIVE on Paid Plans** — If status is TRIALING, PAST_DUE, EXPIRED, or CANCELLED, credit is zero regardless of remaining days. If current plan is FREE, credit is zero.

2. **No Negative Charge** — `chargeAmount = max(price - credit, 0)`. If credit exceeds target price, user pays ₹0, not overpayment. No refund issued (covered by future refund feature).

3. **Fixed Period Days** — MONTHLY = 30 days always, ANNUAL = 365 days always. Not calendar months or leap-year adjusted. Consistency over precision.

4. **Razorpay Minimum Enforcement** — If 0 < chargeAmount < ₹1, round UP to ₹1. Zero remains zero (no order). This prevents Razorpay API rejection for tiny amounts.

5. **Credit Applies Only to Purchase, Not Refund** — Credit is a discount on the new plan's cost. No separate refund transaction issued. Cannot be carried to next cycle or transferred.

6. **Rounding: HALF_UP, 6dp Intermediate, 2dp Final** — All calculations intermediate use 6 decimal places to prevent accumulation. Final amounts round to 2dp (paise precision) using HALF_UP.

7. **No Credit for Downgrades** — Downgrading to a lower price plan or FREE incurs no charge but also no refund of unused days. Sunk cost policy.

8. **Cycle Change Uses Same Credit Model** — Changing from MONTHLY to ANNUAL applies credit for unused MONTHLY days toward ANNUAL cost. Same formulas as plan upgrade.

---

## Failure Scenarios

| Scenario | Exception | HTTP | Recovery |
|---|---|---|---|
| User requests upgrade to non-existent plan | `ResourceException.notFound()` | 404 | User provided invalid targetPlanId. Client should fetch plan list. |
| Target plan is INACTIVE (disabled by admin) | `ResourceException.invalid()` | 400 Bad Request | Plan no longer available. Show user active plans only. |
| User already on requested plan | `ResourceException.invalid()` | 400 Bad Request | Suggested: use /cycle endpoint if they want cycle change. |
| Plan has no price configured for cycle | `ResourceException.invalid()` | 500 Internal Server Error | Data integrity error. Admin misconfigured plan. Check DB. |
| Calculated charge is NaN (BigDecimal overflow) | `ArithmeticException` (uncaught) | 500 | Extremely rare. Price × days causes overflow. Check for absurd plan prices. |
| ProrationService not injected in SubscriptionService | `NullPointerException` | 500 | Spring injection failed. Check Spring config, `@RequiredArgsConstructor` works. |
| Subscription.currentPeriodEnd is NULL | `NullPointerException` in remainingDays() | 500 | Subscription in invalid state (created but period not set). Check invoice creation. |

---

## Known Issues / Limitations

1. **No Refund Support** — Credit is discount only. If user downgrades immediately, paid amount is sunk. Future feature: allow refunds within 14 days.

2. **No Partial Payment** — If credit exactly matches new plan price (rare edge case), user pays ₹0. No "pay what you want" or partial refund mechanisms.

3. **Leap Year Not Handled** — ANNUAL = 365 always, even in leap years. User gets charged same ₹2999 in 2024 (366 days) as 2025 (365 days). Not a real complaint but could be noted.

4. **No Custom Cycle Lengths** — Only MONTHLY (30dp) and ANNUAL (365dp). No 1-month, 3-month, 6-month, 2-year options. Roadmap feature.

5. **Proration Preview Not Cached** — GET /upgrade/preview recalculates every time. For high-traffic tenants, could cache by (currentPlanId, targetPlanId, cycle) key with 5-min TTL.

6. **No A/B Testing on Rounding** — If we change from HALF_UP to ROUND_HALF_DOWN, needs careful migration to avoid refund/overcharge surprises. Currently not versioned.

7. **Credit Doesn't Carry** — Unused credit doesn't roll to next month. ₹10 credit only applies to this upgrade, not future invoices. Design decision to simplify accounting.

---

## Future Improvements

1. **Refund Within 14 Days** — Allow users to request refund if they downgrade immediately. Reduces trust concerns. Requires refund tracking, approval workflow, accounting reconciliation.

2. **Discount Codes / Coupon Integration** — Apply percentage or fixed-amount coupon ON TOP of proration credit. Formula: `charge = (price - credit) - coupon_amount`.

3. **Custom Cycle Lengths** — Support 1-month, 3-month, 6-month, 2-year options. Proration formula generalizes (no hard-coded 30/365).

4. **Proration Preview Caching** — Cache GET /upgrade/preview result for 5 minutes per user. Reduces DB load for browsers that rapidly re-calculate.

5. **Annual Prepaid Discount** — Offer 10% discount if user switches to ANNUAL. Implement via a `discountPercentage` field on Plan or separate coupon system.

6. **Usage-Based Overage Credits** — If user's usage triggers overage charges, apply proration credit if they downgrade before period end. Complex accounting.

7. **Mid-Cycle Billing Adjustments** — Generate credit memos (negative invoices) separately from upgrade invoices for accounting visibility. Currently lumped together.

8. **Proration Preview Explainer** — Add visual breakdown in the response: "You have X days left worth ₹Y. New plan is ₹Z. You save ₹Y by upgrading now." Improves conversion.

---

## Related Documents

- [plan-change-flow.md](./plan-change-flow.md) — How proration fits into 2-step upgrade
- [subscription-lifecycle.md](./subscription-lifecycle.md) — Subscription states that affect credit eligibility
- [payment-processing.md](./payment-processing.md) — How charge amount becomes Razorpay order
- [invoice.md](./invoice.md) — Invoice creation for upgrade (uses charge amount)
- [billing-api.md](../06-api/billing-api.md) — API endpoint signatures for preview
