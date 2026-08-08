---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - billing
  - payment
  - razorpay
  - payment-gateway
  - webhook
  - idempotency
  - verification
  - capture
  - refund
related_documents:
  - ./invoice.md
  - ./subscription-lifecycle.md
  - ../01-architecture/outbox-pattern.md
---

# Payment Processing

## Executive Summary

**Payment processing** is the end-to-end flow from invoice creation through Razorpay payment capture. MTBS uses a **2-step payment model**: (1) Create a Razorpay order (authorization), (2) Verify signature and capture funds (settlement). The system uses idempotency keys to prevent duplicate payments, stores payment records with full audit trail, and handles failures via retry logic with exponential backoff. Without robust payment processing, revenue is uncollected, disputes arise, and invalid payments slip through. This document explains the architecture, security model (HMAC signature verification), failure recovery, and webhook handling.

---

## Context / Problem

### Why 2-Step Payment (Authorization + Capture) Instead of Direct Charge?

**1-Step (Direct)**: User clicks pay → Charge card immediately → Done
- ✅ Simple implementation
- ❌ Cannot auth-only then capture later
- ❌ No fraud detection window
- ❌ Card charged even if user closes browser before confirmation

**2-Step (MTBS)**: User clicks pay → Authorize funds → Verify signature → Capture
- ✅ Auth holds funds, capture settles them (standard card industry model)
- ✅ Can decline fraud during auth window (if integrated with fraud detection)
- ✅ Signature verification ensures legitimate payment (not MITM attack)
- ✅ Refunds are explicit, not automatic (better accounting)
- ✅ Separated concerns: frontend handles UI, backend verifies security

### Why Idempotency Keys?

**Problem**: User clicks "Pay" button twice (accidental double-click)
- Without idempotency: Two Razorpay orders created, user charged twice
- With idempotency key: Razorpay deduplicates, returns same order

Idempotency key format: `pay-{tenantId}-{invoiceId}`
- Unique per (tenant, invoice) pair
- If frontend retries with same key, backend returns existing order (no double-charge)

### Why Signature Verification?

**Attack vector**: Attacker intercepts Razorpay response, modifies payment_id, sends to /api/payments/verify
```
Attacker action: POST /api/payments/verify
{
  "razorpay_payment_id": "attacker_payment_id_for_someone_elses_payment",
  "razorpay_signature": "invalid_signature_they_computed"
}
```

**Defense**: HMAC-SHA256 signature verified using Razorpay's secret key
```
Razorpay computes: 
  signature = HMAC-SHA256(order_id + payment_id, key_secret)

Backend verifies:
  expected  = HMAC-SHA256(order_id + payment_id, key_secret)
  submitted = attacker_signature
  if (expected != submitted) REJECT
```

Only Razorpay and our backend know the secret key. Attacker cannot forge a valid signature.

---

## Dependencies

### Inbound (Who Calls Payment Service)
- `PaymentController.processPayment()` — HTTP POST /api/payments/process/{invoiceId} (user clicks pay)
- `PaymentController.verifyAndCapturePayment()` — HTTP POST /api/payments/verify (user completes checkout)
- `RazorpayWebhookController.handlePaymentSuccess()` — Razorpay webhook (async confirmation)
- `SubscriptionService.activateUpgradeAfterPayment()` — Payment verified, activate upgrade
- `PaymentRetryJob` — Scheduled job attempts retries for failed payments

### Outbound (What Payment Calls)
- `RazorpayPaymentGateway.createOrder()` — Create order for payment
- `RazorpayPaymentGateway.verifyPaymentSignature()` — Verify HMAC signature
- `RazorpayPaymentGateway.capturePayment()` — Capture authorized funds
- `RazorpayPaymentGateway.refundPayment()` — Issue refund
- `InvoiceService.markInvoicePaid()` — Mark invoice as PAID
- `SubscriptionService.activateUpgradeAfterPayment()` — Activate subscription after payment
- `OutboxEventPublisher.save()` — Publish PaymentCapturedEvent, AuditLogEvent
- `PaymentCapturedEventPublisher.publish()` — Publish payment success to listeners

### Configuration
- `razorpay.key-id` — Public key (sent to client for checkout)
- `razorpay.key-secret` — Private key (NEVER sent to client, used for signature verification)
- `mtbs.payment.max-retry-count: 3` — Max failed attempts before suspension
- `mtbs.payment.retry-backoff: EXPONENTIAL` — Retry timing strategy (1s, 2s, 4s, etc.)
- `razorpay.webhook-secret` — Used to verify incoming webhooks from Razorpay

---

## Design / Implementation

### Payment Entity

```java
@Entity
@Table(name = "payments")
public class Payment extends AuditableEntity {
    
    // ── Core ──
    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;              // Which invoice this payment is for
    
    private BigDecimal amount;           // Amount in INR (e.g., 1499.50)
    private String currency;             // "INR" (only supported currently)
    
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;        // PENDING, SUCCEEDED, FAILED
    
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod; // CARD, NETBANKING, etc.
    
    // ── Razorpay References ──
    private String razorpayOrderId;      // Order ID from Razorpay (step 1)
    private String razorpayPaymentId;    // Payment ID from Razorpay (after auth)
    private String razorpaySignature;    // HMAC signature (verified on backend)
    
    // ── Idempotency & Retries ──
    @Column(unique = true)
    private String idempotencyKey;       // Format: "pay-456-100" (tenantId-invoiceId)
    
    private Integer retryCount;          // 0, 1, 2, 3 (max)
    private Instant nextRetryAt;         // When to retry if failed
    
    // ── Failure Info ──
    private String failureCode;          // Razorpay error code (optional)
    private String failureMessage;       // Razorpay error message (optional)
    
    // ── Success Info ──
    private Instant paidAt;              // When payment was captured
}
```

### Payment Status States

```
PENDING
  ├─ Order created, user hasn't paid yet
  ├─ Waiting for Razorpay checkout
  ├─ Transition: User completes checkout → verifyPaymentSignature() → SUCCEEDED
  ├─ Transition: User closes browser without paying → Razorpay order expires (15 min) → abandoned
  └─ Transition: Razorpay webhook sends error → FAILED

SUCCEEDED
  ├─ Payment verified and captured
  ├─ Funds held by Razorpay, ready to settle
  ├─ Invoice marked as PAID
  ├─ Subscription activated (if trial) or upgraded (if pending upgrade)
  ├─ Refund events triggered
  └─ No transition beyond (can be refunded)

FAILED
  ├─ Payment authorization failed (card invalid, declined, etc.)
  ├─ retryCount < 3 → eligible for retry
  ├─ retryCount = 3 → max retries exhausted, subscription suspended
  ├─ failureCode + failureMessage logged for audit
  ├─ Transition: Manual retry → new Razorpay order → PENDING
  ├─ Transition: Razorpay retry → SUCCEEDED
  └─ Transition: Manual action → refund initiated (rare)
```

### Payment Flow: Step 1 (Initiate)

**Endpoint**: `POST /api/payments/process/{invoiceId}`

```java
@Transactional
public OrderResponse processPayment(Long invoiceId) {
    // Fetch invoice
    Invoice invoice = invoiceRepository.findById(invoiceId)
        .orElseThrow(() -> ResourceException.notFound("Invoice", invoiceId));
    
    // Validate invoice is not already paid
    if (invoice.getStatus() == InvoiceStatus.PAID) {
        throw PaymentException.paymentAlreadyProcessed();
    }
    
    // Generate idempotency key
    String idempotencyKey = "pay-" + TenantContext.getTenantId() + "-" + invoiceId;
    
    // Check if order already created (idempotency)
    Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
        Payment existingPayment = existing.get();
        return OrderResponse.builder()
            .orderId(existingPayment.getRazorpayOrderId())
            .amount(existingPayment.getAmount().multiply(BigDecimal.valueOf(100)).longValue())
            .currency(existingPayment.getCurrency())
            .build();
    }
    
    // Create new Razorpay order
    long amountInPaise = invoice.getTotalAmount()
        .multiply(BigDecimal.valueOf(100))
        .longValue();
    
    OrderResponse order = paymentGateway.createOrder(
        amountInPaise,
        invoice.getCurrency(),
        idempotencyKey  // Razorpay's receipt parameter
    );
    
    // Store payment record
    Payment payment = Payment.builder()
        .invoiceId(invoice.getId())
        .amount(invoice.getTotalAmount())
        .currency(invoice.getCurrency())
        .status(PaymentStatus.PENDING)
        .razorpayOrderId(order.getOrderId())
        .idempotencyKey(idempotencyKey)
        .retryCount(0)
        .build();
    
    paymentRepository.save(payment);
    
    // Publish audit event
    outboxEventPublisher.save(AuditLogEvent.builder()
        .action(PAYMENT_INITIATED)
        .entityType(PAYMENT)
        .entityId(payment.getId())
        .description("Payment initiated: ₹" + payment.getAmount())
        .build());
    
    return order;
}
```

**Response sent to frontend**:
```json
{
  "orderId": "order_KjhGfD123abc",
  "amount": 149900,
  "currency": "INR",
  "keyId": "rzp_live_abc123..."
}
```

**Frontend opens Razorpay**:
```javascript
Razorpay.openCheckout({
    key: response.keyId,
    order_id: response.orderId,
    amount: response.amount,
    currency: response.currency,
    handler: function(resp) {
        // User completed payment
        fetch('/api/payments/verify', {
            method: 'POST',
            body: JSON.stringify({
                razorpay_payment_id: resp.razorpay_payment_id,
                razorpay_order_id: resp.razorpay_order_id,
                razorpay_signature: resp.razorpay_signature
            })
        });
    }
});
```

### Payment Flow: Step 2 (Verify & Capture)

**Endpoint**: `POST /api/payments/verify`

```java
@Transactional
public PaymentResponse verifyAndCapturePayment(VerifyPaymentRequest request) {
    // Find payment by order ID
    Payment payment = paymentRepository.findByRazorpayOrderId(request.getRazorpayOrderId())
        .orElseThrow(() -> ResourceException.notFound("Payment", request.getRazorpayOrderId()));
    
    // ✅ CRITICAL: Verify HMAC signature (prevents forged payments)
    boolean valid = paymentGateway.verifyPaymentSignature(
        request.getRazorpayOrderId(),
        request.getRazorpayPaymentId(),
        request.getRazorpaySignature()
    );
    
    if (!valid) {
        log.warn("Payment signature verification failed — potential fraud attempt");
        throw PaymentException.invalidSignature();
    }
    
    // Capture the payment
    long amountInPaise = payment.getAmount()
        .multiply(BigDecimal.valueOf(100))
        .longValue();
    
    try {
        paymentGateway.capturePayment(request.getRazorpayPaymentId(), amountInPaise);
    } catch (RazorpayException e) {
        log.error("Payment capture failed: {}", e.getMessage());
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureCode(e.getCode());
        payment.setFailureMessage(e.getMessage());
        paymentRepository.save(payment);
        throw PaymentException.razorpayError("CAPTURE_FAILED", e.getMessage());
    }
    
    // Update payment record
    payment.setRazorpayPaymentId(request.getRazorpayPaymentId());
    payment.setRazorpaySignature(request.getRazorpaySignature());
    payment.setStatus(PaymentStatus.SUCCEEDED);
    payment.setPaidAt(Instant.now());
    paymentRepository.save(payment);
    
    // Mark invoice as PAID
    invoiceService.markInvoicePaid(
        payment.getInvoiceId(),
        request.getRazorpayPaymentId(),
        Instant.now()
    );
    
    // Publish events
    paymentCapturedEventPublisher.publish(
        new PaymentCapturedEvent(PAYMENT_CAPTURED, TenantContext.getTenantId(), payment.getInvoiceId())
    );
    
    outboxEventPublisher.save(AuditLogEvent.builder()
        .action(PAYMENT_COMPLETED)
        .entityType(PAYMENT)
        .entityId(payment.getId())
        .description("Payment captured: ₹" + payment.getAmount())
        .build());
    
    // Check if this payment was for a subscription upgrade
    Subscription subscription = subscriptionRepository.findByUpgradePendingInvoiceId(payment.getInvoiceId()).orElse(null);
    if (subscription != null) {
        subscriptionService.activateUpgradeAfterPayment(payment.getInvoiceId());
    }
    
    return paymentMapper.toResponse(payment);
}
```

### HMAC Signature Verification (RazorpayPaymentGateway)

```java
@Override
public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
    try {
        JSONObject attributes = new JSONObject();
        attributes.put("razorpay_order_id", orderId);
        attributes.put("razorpay_payment_id", paymentId);
        attributes.put("razorpay_signature", signature);
        
        // Razorpay SDK computes:
        //   expected = HMAC-SHA256(orderId + paymentId, keySecret)
        // and compares with submitted signature
        Utils.verifyPaymentSignature(attributes, keySecret);
        
        return true;
    } catch (RazorpayException e) {
        log.warn("Signature verification failed for order: {}", orderId);
        return false;  // Signature invalid — potential attack
    }
}
```

### Failure Handling & Retries

**Scenario**: User's card declined during payment

```
Step 1: POST /api/payments/process/{invoiceId}
  ↓ Order created, status=PENDING

[Frontend Razorpay checkout]
[User enters invalid card]

Razorpay response: { error: "Card declined" }

User clicks "Try again"
  ↓
Step 2: POST /api/payments/verify [with invalid signature/declined payment]
  ↓ PaymentService.verifyAndCapturePayment()
  ├─ Signature verification fails OR capture fails
  ├─ Update payment status=FAILED
  ├─ Set failureCode="card_declined"
  ├─ Set nextRetryAt = Instant.now() + 1 second (exponential backoff)
  └─ Throw PaymentException

Frontend receives: 400 Bad Request
User clicks "Retry payment"
  ↓
Step 3: POST /api/payments/{paymentId}/retry
  ├─ Check retryCount < 3
  ├─ Create NEW Razorpay order (increment retryCount)
  ├─ Return new order to frontend
  └─ Loop back to Step 2

If retryCount = 3 (max retries exhausted):
  ├─ Operation denied
  └─ Subscription status → PAST_DUE (manual payment required)
```

**Code**:
```java
@Transactional
public OrderResponse retryFailedPayment(Long paymentId) {
    Payment payment = paymentRepository.findById(paymentId)
        .orElseThrow(() -> ResourceException.notFound("Payment", paymentId));
    
    if (payment.getRetryCount() >= MAX_RETRY_COUNT) {
        throw PaymentException.maxRetriesExceeded();
    }
    
    // Create new order
    long amountInPaise = payment.getAmount()
        .multiply(BigDecimal.valueOf(100))
        .longValue();
    
    OrderResponse order = paymentGateway.createOrder(
        amountInPaise,
        payment.getCurrency(),
        payment.getIdempotencyKey()  // Same key, Razorpay deduplicates
    );
    
    // Update retry count
    payment.setRetryCount(payment.getRetryCount() + 1);
    payment.setStatus(PaymentStatus.PENDING);  // Back to pending
    payment.setRazorpayOrderId(order.getOrderId());  // New order
    paymentRepository.save(payment);
    
    return order;
}
```

### Webhook Handling (Async Confirmation)

**Razorpay sends webhook**: `POST /webhooks/razorpay`

```java
@PostMapping("/webhooks/razorpay")
public ResponseEntity<String> handleWebhook(@RequestBody String payload, 
                                            @RequestHeader("X-Razorpay-Signature") String signature) {
    // Verify webhook signature (different from payment signature)
    boolean valid = verifyWebhookSignature(payload, signature);
    if (!valid) {
        return ResponseEntity.status(401).body("Invalid signature");
    }
    
    // Parse webhook event
    JSONObject event = new JSONObject(payload);
    String eventType = event.getString("event");
    JSONObject eventData = event.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
    
    String razorpayPaymentId = eventData.getString("id");
    String razorpayOrderId = eventData.getString("order_id");
    
    if ("payment.authorized".equals(eventType)) {
        // Payment authorized, safe to capture
        paymentService.handlePaymentAuthorized(razorpayPaymentId, razorpayOrderId);
    } else if ("payment.failed".equals(eventType)) {
        // Payment failed on Razorpay side
        String failureCode = eventData.optString("error_code");
        String failureMessage = eventData.optString("error_description");
        paymentService.handlePaymentFailed(razorpayPaymentId, failureCode, failureMessage);
    }
    
    return ResponseEntity.ok("OK");  // Ack to Razorpay
}
```

### Idempotency: Duplicate Detection

**Scenario**: User's internet connection drops, retry button clicked multiple times

```
Click 1: POST /api/payments/process/100
  ├─ idempotencyKey = "pay-456-100"
  ├─ Create Razorpay order "order_ABC"
  ├─ Save payment {orderId: "order_ABC", idempotencyKey: "pay-456-100"}
  └─ Return "order_ABC"

[Connection drops, retry]

Click 2: POST /api/payments/process/100
  ├─ idempotencyKey = "pay-456-100"
  ├─ Check: findByIdempotencyKey("pay-456-100") → returns existing payment
  ├─ Return existing "order_ABC" (no new order created)
  └─ Razorpay.openCheckout(order_ABC)  [same order, no double-charge]
```

---

## Flow

### Payment Lifecycle (2-Step)

```
┌──────────────────────────────────┐
│  User has OPEN invoice of ₹1,499 │
│  Clicks "Pay now" button         │
└──────────────┬───────────────────┘
               │
               ▼
    ┌──────────────────────────────┐
    │ POST /payments/process/100   │
    │ (PaymentController)          │
    └──────────────┬───────────────┘
                   │
                   ▼
    ┌──────────────────────────────────────────┐
    │ PaymentService.processPayment()          │
    │ • Check invoiceId valid, not PAID        │
    │ • Generate idempotencyKey="pay-456-100"│
    │ • Check for existing order (dedup)       │
    │ • Call Razorpay.createOrder()            │
    │ • Save Payment {status=PENDING}          │
    │ • Publish PAYMENT_INITIATED audit event  │
    │ • Return OrderResponse                   │
    └──────────────┬───────────────────────────┘
                   │
                   ▼
    ┌──────────────────────────────────────────┐
    │ HTTP 200 OK                              │
    │ {                                        │
    │   orderId: "order_KjhGfD123",            │
    │   amount: 149900 (paise),                │
    │   currency: "INR",                       │
    │   keyId: "rzp_live_abc..."               │
    │ }                                        │
    └──────────────┬───────────────────────────┘
                   │
                   ▼
    ┌──────────────────────────────────────────┐
    │ Frontend (JavaScript)                    │
    │ Razorpay.openCheckout({                  │
    │   key: keyId,                            │
    │   order_id: orderId,                     │
    │   amount: amount,                        │
    │   handler: (resp) => {                   │
    │     POST /api/payments/verify            │
    │   }                                      │
    │ })                                       │
    └──────────────┬───────────────────────────┘
                   │
                   ▼
    ┌──────────────────────────────────────────┐
    │ User enters card details in Razorpay     │
    │ Razorpay authorizes payment              │
    │ Razorpay returns signature + payment_id  │
    └──────────────┬───────────────────────────┘
                   │
   ┌───────────────┴────────────────┐
   │                                │
   ▼                                ▼
Payment          Payment
Authorized       Declined
   │                 │
   │ User clicks     │ Card rejected
   │ "Confirm"       │ or invalid card
   │                 │
   ▼                 ▼
┌────────────────────┐ ┌──────────────────┐
│ POST /payments/    │ │ POST /payments/   │
│ verify             │ │ verify            │
│ {payment_id: ...,  │ │ [invalid sig/     │
│  signature: ...}   │ │  failed payment]  │
└────────┬───────────┘ └────────┬──────────┘
         │                      │
         ▼                      ▼
    ┌─────────────────────────────┐
    │ PaymentService.verify()     │
    │ • Verify signature (HMAC)   │
    │ • Signature valid?          │
    └───┬─────────┬───────────┬───┘
        │         │           │
    YES │ NO      │ Exception │
        │         │           │
        ▼         ▼           ▼
    ┌──────┐  ┌─────────┐  ┌──────┐
    │ Call │  │ Reject: │  │ Set  │
    │Capture│ │Invalid  │  │ FAIL │
    │      │  │Signature│  │      │
    └───┬──┘  └────┬────┘  └───┬──┘
        │         │            │
        ▼         ▼            ▼
    ┌──────────────────────────────┐
    │ Update Payment               │
    │ status = SUCCEEDED / FAILED  │
    │ failureCode (if failed)       │
    │ paidAt (if succeeded)         │
    └──────────────┬───────────────┘
                   │
        ┌──────────┴──────────┐
        │                     │
        ▼ (if SUCCEEDED)      ▼ (if FAILED)
    ┌──────────────┐    ┌──────────────┐
    │ Mark invoice │    │ Set nextRetry│
    │ as PAID      │    │ (backoff)    │
    │              │    │ Show retry   │
    │ Activate     │    │ button       │
    │ subscription │    │              │
    │              │    │ User can:    │
    │ Publish      │    │ • Retry now  │
    │ PAYMENT_     │    │ • Retry later│
    │ CAPTURED evt │    │              │
    │              │    │ Max 3 retries│
    └──────────────┘    └──────────────┘
```

---

## Code References

| Class | Tag | Method/Purpose | Role |
|-------|-----|----------------|------|
| `Payment` | [BIL-4] | Entity mapping | Stores payment record for each order |
| `PaymentService` | [BIL-16] | `processPayment()` | Step 1: create Razorpay order + payment record |
| `PaymentService` | [BIL-16] | `verifyAndCapturePayment()` | Step 2: verify signature + capture funds |
| `PaymentService` | [BIL-16] | `retryFailedPayment()` | Create new order for retry (max 3x) |
| `PaymentService` | [BIL-16] | `refundPayment()` | Issue full/partial refund to card |
| `PaymentGatewayPort` | [BIL-21] | Interface | Abstraction for payment provider |
| `RazorpayPaymentGateway` | [BIL-22] | `createOrder()` | Create Razorpay order |
| `RazorpayPaymentGateway` | [BIL-22] | `verifyPaymentSignature()` | Verify HMAC signature (security) |
| `RazorpayPaymentGateway` | [BIL-22] | `capturePayment()` | Capture authorized payment |
| `RazorpayPaymentGateway` | [BIL-22] | `refundPayment()` | Initiate refund with Razorpay |
| `PaymentController` | [BIL-42] | POST `/process/{invoiceId}` | Step 1 endpoint |
| `PaymentController` | [BIL-42] | POST `/verify` | Step 2 endpoint |
| `PassmentController` | [BIL-42] | POST `/{id}/retry` | Retry endpoint for failed payment |
| `RazorpayWebhookController` | [BIL-45] | POST `/webhooks/razorpay` | Receive payment status from Razorpay |
| `PaymentResponse` | [BIL-48] | DTO | API response shape after payment verification |
| `VerifyPaymentRequest` | [BIL-54] | DTO | Request body with signature + payment_id |

---

## Rules / Constraints

1. **Razorpay signature MUST be verified before marking payment SUCCEEDED** — If signature verification is skipped (e.g., "trust the frontend"), an attacker can forge a payment by submitting a fake signature. The HMAC-SHA256 verification is the only security boundary between legitimate and fraudulent payments.

2. **Idempotency key MUST be unique per (tenant, invoice)** — Two different tenants processing payment for their invoices might use the same invoice ID. Idempotency key format must include tenantId to prevent cross-tenant collisions. Format: `pay-{tenantId}-{invoiceId}`.

3. **Amount in Razorpay MUST be in paise (smallest currency unit), not INR** — Razorpay expects amounts as integers (paise), not decimals (INR). ₹ 1,499.50 must be sent as 149950 paise. If amount sent incorrectly, Razorpay rejects the request.

4. **Payment capture MUST be transactional with invoice status change** — If payment.capture() succeeds but invoiceService.markInvoicePaid() fails, invoices is never marked PAID and revenue is not recognized. Both must happen in same @Transactional method, or manual correction needed.

5. **Retry count MUST be checked before allowing subsequent retries** — If max retries (3) is exceeded and a retry is attempted, throw PaymentException immediately. Otherwise, unlimited retries could send Razorpay excessive load.

6. **Failure reasons MUST be logged for debugging** — When payment fails, store failureCode + failureMessage from Razorpay response. Later analysis of these error codes helps identify patterns (card declined, insufficient funds, invalid card, etc.).

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| Invoice already PAID (duplicate payment attempt) | `PaymentException.paymentAlreadyProcessed()` | 409 Conflict | Check invoice status before paying; redirect to success page if already paid |
| Razorpay order creation fails (API down) | `PaymentException.orderCreationFailed()` | 503 Service Unavailable | Razorpay is temporarily down; retry after service recovery |
| Payment signature invalid (attacker forged signature) | `PaymentException.invalidSignature()` | 400 Bad Request | Reject payment; log security incident; alert ops; do not mark payment succeeded |
| Payment capture fails (insufficient funds, card blocked) | `PaymentException.razorpayError("CAPTURE_FAILED")` | 400/503 depending on error | Set status=FAILED, set retryCount; user can retry or contact support |
| Database concurrent update during payment capture | `OptimisticLockException` (version mismatch) | 409 Conflict | Retry the verify endpoint; Hibernate's @Version prevents lost updates |
| Duplicate idempotency_key (two payments for same invoice) | Unique constraint violation caught, existing payment returned | 200 OK | Idempotency returns existing order instead of creating duplicate |
| Max retries exceeded (3 failed attempts) | `PaymentException.maxRetriesExceeded()` | 400 Bad Request | Subscription moves to PAST_DUE; manual intervention or contact support |
| Webhook signature verification fails (attacker sends fake webhook) | Webhook rejected, 401 returned to Razorpay | 401 Unauthorized | Do not process webhook; log security incident; no state change |
| Payment verified but subscription cannot be found | `ResourceException.notFound()` | 404 Not Found | Data inconsistency; manual investigation; check if subscription was deleted |

---

## Edge Cases

- **Concurrency**: Two simultaneous requests verify same payment. Database row-level lock (on Payment entity) + optimistic locking (@Version) prevents double-capture. Second request gets OptimisticLockException and can retry.

- **Timezone**: Payment times (created_at, paidAt, nextRetryAt) are TIMESTAMPTZ (UTC). Retry backoff calculation uses Instant arithmetic, no timezone confusion. Razorpay also uses UTC.

- **Partial Payment**: Razorpay supports partial authorization (e.g., user pays $50 of $100 order). Current code assumes full payment (amount in payment must equal invoice.totalAmount). Partial payment logic is deferred; would require: (1) Store partial amount on Payment entity, (2) Recalculate remaining dues, (3) Generate catch-up invoice for difference.

- **Refund Disputes**: If user issues chargeback after payment captured, Razorpay reverses the payment. PaymentService.handleRefund() marks payment status=REFUNDED, but subscription is already activated. Manual action needed: downgrade subscription or dispute chargeback.

- **Idempotency Expiration**: After some time (e.g., 1 year), old idempotency keys should be purged to keep database size manageable. Scheduled job can delete Payment records where status=SUCCEEDED AND paidAt < 1 year ago (safe to delete old history).

- **Razorpay API Changes**: If Razorpay API version changes or signature algorithm changes, signature verification logic must be updated. PaymentGatewayPort abstraction allows swapping RazorpayPaymentGateway with alternative implementation.

---

## Known Issues / Limitations

1. **No support for partial payments** — If invoice is ₹100 and user pays ₹50, payment is rejected (amount mismatch). Should support partial payments and generate catch-up invoice for remainder.

2. **Refunds are immediate, not scheduled** — When refund is issued, funds returned immediately. No hold period or refund delay. If chargeback arrives after refund processed, accounting mismatch.

3. **No fraud detection integration** — Signature verification prevents forgery, but not fraud (stolen card, etc.). No 3D Secure or risk analysis. Integration with fraud detection service would catch high-risk transactions.

4. **Retry backoff is hardcoded** — Retry timing (1s, 2s, 4s, ...) is in code, not configurable. If retry timing needs adjustment, code change required.

5. **No payment reconciliation job** — If a payment succeeds on Razorpay but fails locally (e.g., invoice update fails), there's no scheduled job to detect and fix the mismatch. Manual reconciliation required.

6. **Webhook delivery not guaranteed** — Razorpay may not deliver webhook (network failure, server down, etc.). Payment verified via frontend /verify endpoint is safe, but webhook-only payments could be lost.

---

## Future Improvements

1. Implement payment reconciliation job — Periodically query Razorpay for all orders created in last 24h, check if they match local Payment records. Alert if mismatch detected.

2. Add support for partial payments — Store partial amount on Payment entity; generate catch-up invoice for remainder; allow multiple payments per invoice.

3. Integrate 3D Secure (3DS) authentication — For high-risk transactions (large amount, new card), require 3D Secure challenge. Reduces fraud and chargebacks.

4. Implement refund tracking — Track refund status (pending, failed, succeeded) separately from payment status. Allow refund cancellation if issued by mistake.

5. Add payment method preferences — Allow tenant to set preferred payment method (card, net banking, etc.). Bypass method selection step if preferred method available.

6. Implement chargeback handling — When Razorpay sends chargeback webhook, automatically downgrade subscription or flag for manual review. Track chargeback ratio per tenant.

---

## Related Documents
- [subscription-lifecycle.md](./subscription-lifecycle.md) — Subscription state changes after payment verified
- [invoice.md](./invoice.md) — Invoice created before payment is initiated
- [system-design.md](../01-architecture/system-design.md) — Architecture overview
- [request-flow.md](../01-architecture/request-flow.md) — HTTP filter chain
- [outbox-pattern.md](../01-architecture/outbox-pattern.md) — Event delivery (payment events)
- [event-flow.md](../01-architecture/event-flow.md) — PaymentCapturedEvent listeners
- [authorization-rbac.md](../03-security/authorization-rbac.md) — Related Phase 2 document on PERMISSION_BILLING_MANAGE
- [error-handling.md](../06-api/error-handling.md) — Related Phase 3 document on API errors (PaymentException)
- [retry-mechanisms.md](../09-jobs/retry-mechanisms.md) — Related Phase 4 document on payment retry job
