# ADR-003: Razorpay 2-Step Payment Verification Architecture

**Date:** May 2026  
**Status:** Accepted  
**Decision Maker:** Payments Team  
**Stakeholders:** Billing, Security, DevOps

---

## Problem Statement

The system must process payments reliably through Razorpay (India's leading payment gateway) while handling:

1. **Asynchronous Payment Processing**
   - Customer creates order on frontend
   - Payment processed by Razorpay (can take seconds)
   - Webhook delivery delayed or lost
   - How do we know when payment succeeded?

2. **Webhook Reliability Challenges**
   - Webhook delivered multiple times (at-least-once)
   - Network failures cause retries
   - What if app crashes during webhook processing?

3. **Security Requirements**
   - Verify webhook comes from Razorpay (not forged)
   - Prevent replay attacks (same webhook processed twice)
   - PCI compliance (no card data stored in our DB)

4. **Race Conditions**
   - Customer refreshes page immediately after payment
   - What if webhook hasn't arrived yet?
   - How to reconcile manual payment with webhook?

**Requirements:**
- ✅ Payment status always accurate (single source of truth)
- ✅ Webhook signature verification (HMAC-SHA256)
- ✅ Webhook idempotency (same webhook not processed twice)
- ✅ Webhook resilience (survive application crash)
- ✅ Manual payment reconciliation (for testing, disputes)
- ✅ Audit trail for compliance

---

## Options Evaluated

### Option 1: Single-Step (Poll Razorpay API)

```
Flow:
1. Create order
2. Poll Razorpay: "Is payment done?"
3. If yes → mark as CAPTURED
4. If no → retry later
```

**Pros:**
- ✅ Simple, no webhook complexity
- ✅ Single source of truth

**Cons:**
- ❌ Expensive API calls (poll every second)
- ❌ Latency (payment done, but we don't know for seconds)
- ❌ Doesn't scale (thousands of polls per second)
- ❌ Razorpay rate limits

---

### Option 2: Webhook-Only (Fire-and-Forget)

```
Flow:
1. Create order
2. Razorpay sends webhook when payment captured
3. Mark payment as CAPTURED
```

**Pros:**
- ✅ No polling overhead
- ✅ Real-time updates

**Cons:**
- ❌ Webhook delivery not guaranteed
- ❌ What if webhook lost? (Payment succeeded but we don't know)
- ❌ Manual reconciliation impossible
- ❌ No fallback if webhook fails

---

### Option 3: 2-Step Verification (SELECTED)

```
Flow:
┌─────────────────────────────────────────────┐
│ Step 1: Create Order (API)                  │
├─────────────────────────────────────────────┤
│ POST /orders                                │
│ → Razorpay creates order                    │
│ → Return order_id to frontend               │
│ → Payment status = PENDING                  │
└─────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────┐
│ Step 2a: Webhook Verification (async)       │
├─────────────────────────────────────────────┤
│ Razorpay sends webhook                      │
│ → Verify HMAC signature                     │
│ → Check payment status                      │
│ → Update payment to CAPTURED                │
│ → Send confirmation email                   │
└─────────────────────────────────────────────┘
         │                   
         │ (OR if webhook lost)
         │
         ▼
┌─────────────────────────────────────────────┐
│ Step 2b: Polling Fallback (sync)            │
├─────────────────────────────────────────────┤
│ Customer checks payment status              │
│ → Query Razorpay: "Is order paid?"          │
│ → If yes: Mark as CAPTURED, send email      │
│ → If no: Show "waiting for payment" status  │
└─────────────────────────────────────────────┘
```

**Pros:**
- ✅ Fast (webhook when ready)
- ✅ Reliable (polling fallback)
- ✅ Secure (webhook signature verified)
- ✅ Idempotent (duplicate webhooks handled)
- ✅ Audit trail (all events logged)
- ✅ Works at scale

**Cons:**
- ❌ More complex implementation
- ❌ Two verification paths (webhook + polling)

---

## Decision

**SELECTED: Option 3 — 2-Step Verification**

### Rationale

1. **Reliability** — Webhook for speed + polling for fallback
2. **Security** — HMAC signature verification mandatory
3. **Scalability** — Minimal API calls to Razorpay
4. **Compliance** — PCI-DSS compliant (no card data stored)
5. **Production-Grade** — Used by Stripe, PayPal

### Trade-offs Accepted

- **Complexity** — Two verification paths to implement
- **Eventual Consistency** — Payment status may update with slight delay

---

## Implementation

### 1. Create Order (Step 1: Frontend Initiates)

```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentGatewayService {
    
    private final RazorpayClient razorpayClient;
    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    
    /**
     * Step 1: Create Razorpay order for payment
     * Called by frontend before customer enters card details
     */
    public CreatePaymentLinkResponse createPaymentOrder(
        CreatePaymentOrderRequest request  // invoiceId, amount
    ) {
        Long invoiceId = request.getInvoiceId();
        BigDecimal amount = request.getAmount();
        
        // Create payment entity (status = PENDING)
        Payment payment = Payment.builder()
            .invoiceId(invoiceId)
            .amount(amount)
            .status(PaymentStatus.PENDING)
            .razorpayOrderId(null)  // Will be set after order creation
            .build();
        
        payment = paymentRepository.save(payment);
        
        try {
            // Call Razorpay API to create order
            RazorpayOrder razorpayOrder = razorpayClient.createOrder(
                CreateOrderRequest.builder()
                    .amount(amount.multiply(BigDecimal.valueOf(100)).longValue())  // In paise (₹1 = 100p)
                    .currency("INR")
                    .receipt("INV-" + invoiceId)
                    .description("Invoice payment")
                    .build()
            );
            
            // Store Razorpay order ID
            payment.setRazorpayOrderId(razorpayOrder.getId());
            paymentRepository.save(payment);
            
            log.info("Created Razorpay order: {} for invoice {}", 
                razorpayOrder.getId(), invoiceId);
            
            return CreatePaymentLinkResponse.builder()
                .paymentId(payment.getId())
                .razorpayOrderId(razorpayOrder.getId())
                .amount(amount)
                .currency("INR")
                .build();
            
        } catch (Exception e) {
            log.error("Failed to create Razorpay order for invoice {}", invoiceId, e);
            throw new PaymentException("Failed to create payment order");
        }
    }
}
```

### 2. Frontend Payment Flow

```javascript
// Frontend: After customer submits card details
async function capturePayment(paymentId, razorpayOrderId) {
    // Call Razorpay client-side checkout
    const razorpay = new Razorpay({
        key: process.env.RAZORPAY_KEY_ID,
        order_id: razorpayOrderId,
        handler: async function(response) {
            // Razorpay returned payment ID
            // Now verify on backend
            await api.post('/payments/verify', {
                paymentId: paymentId,
                razorpayPaymentId: response.razorpay_payment_id,
                razorpaySignature: response.razorpay_signature
            });
        },
        prefill: {
            email: currentUser.email,
            name: currentUser.name
        }
    });
    
    razorpay.open();
}
```

### 3. Verify Payment (Webhook OR Manual)

```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class WebhookHandlerService {
    
    private final PaymentRepository paymentRepository;
    private final RazorpayWebhookVerifier webhookVerifier;
    private final OutboxEventRepository outboxEventRepository;
    
    /**
     * Step 2a: Handle Razorpay webhook (payment.authorized)
     * Webhook sent by Razorpay when payment captured
     */
    public void handlePaymentWebhook(RazorpayWebhookRequest request) {
        try {
            // Step 1: Verify webhook signature (CRITICAL for security)
            if (!webhookVerifier.verify(request)) {
                log.warn("Webhook signature verification failed: {}", request.getId());
                throw new WebhookException("Invalid signature");
            }
            
            // Step 2: Extract payment details
            String razorpayPaymentId = request.getPayload().getPayment().getId();
            String razorpayOrderId = request.getPayload().getPayment().getOrderId();
            String razorpaySignature = request.getPayload().getPayment().getSignature();
            
            // Step 3: Find payment in our database
            Payment payment = paymentRepository.findByRazorpayOrderId(razorpayOrderId)
                .orElseThrow(() -> new PaymentException("Order not found"));
            
            // Step 4: Check idempotency (webhook might be delivered twice)
            if (payment.getStatus() == PaymentStatus.CAPTURED) {
                log.info("Payment {} already captured, skipping duplicate webhook", 
                    payment.getId());
                return;  // ✅ Idempotent
            }
            
            // Step 5: Verify payment details with Razorpay
            RazorpayPaymentDetails razorpayPayment = razorpayClient.getPayment(razorpayPaymentId);
            
            if (!razorpayPayment.getStatus().equals("captured")) {
                log.warn("Payment status is {}, not 'captured'", razorpayPayment.getStatus());
                return;
            }
            
            // Step 6: Update payment status
            payment.setStatus(PaymentStatus.CAPTURED);
            payment.setRazorpayPaymentId(razorpayPaymentId);
            payment.setRazorpaySignature(razorpaySignature);
            payment.setCapturedAt(Instant.now());
            paymentRepository.save(payment);
            
            // Step 7: Publish event (outbox pattern)
            OutboxEvent event = OutboxEvent.builder()
                .aggregateId(payment.getId())
                .aggregateType("Payment")
                .eventType("PaymentCaptured")
                .payload(new PaymentCapturedEvent(
                    payment.getId(),
                    payment.getInvoiceId(),
                    payment.getAmount(),
                    Instant.now()
                ))
                .status(OutboxStatus.PENDING)
                .build();
            
            outboxEventRepository.save(event);
            
            // Step 8: Update invoice status (via event handler)
            // This happens asynchronously via event listener
            
            log.info("Payment {} captured via webhook", payment.getId());
            
        } catch (Exception e) {
            log.error("Error handling webhook", e);
            throw e;
        }
    }
}
```

### 4. Webhook Signature Verification

```java
@Service
@Slf4j
public class RazorpayWebhookVerifier {
    
    private final String webhookSecret;  // From config
    
    /**
     * Verify HMAC-SHA256 signature of webhook
     * 
     * Razorpay sends:
     * - X-Razorpay-Signature header: HMAC-SHA256(body, webhook_secret)
     * - body: JSON payload
     * 
     * We verify signature matches to ensure webhook is from Razorpay
     */
    public boolean verify(RazorpayWebhookRequest request) {
        try {
            String body = request.getRawBody();  // Original JSON string
            String signature = request.getHeader("X-Razorpay-Signature");
            
            // Compute HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(), "HmacSHA256"));
            byte[] computedSignature = mac.doFinal(body.getBytes());
            String computedSignatureHex = new String(Hex.encode(computedSignature));
            
            // Compare with received signature (constant-time comparison)
            boolean isValid = MessageDigest.isEqual(
                signature.getBytes(),
                computedSignatureHex.getBytes()
            );
            
            if (!isValid) {
                log.warn("Webhook signature mismatch: received={}, computed={}", 
                    signature, computedSignatureHex);
            }
            
            return isValid;
            
        } catch (Exception e) {
            log.error("Error verifying webhook signature", e);
            return false;
        }
    }
}
```

### 5. Manual Verification Endpoint (Polling Fallback)

```java
@RestController
@RequestMapping("/api/v*/payments")
@RequiredArgsConstructor
@Secured("PERMISSION_BILLING_MANAGE")
@Transactional
public class PaymentController {
    
    private final PaymentService paymentService;
    private final RazorpayClient razorpayClient;
    
    /**
     * Step 2b: Manual verification (fallback if webhook lost)
     * Frontend can call this if webhook hasn't arrived after 10 seconds
     */
    @PostMapping("/{paymentId}/verify")
    public ResponseEntity<PaymentResponse> verifyPayment(
        @PathVariable Long paymentId
    ) {
        // This endpoint performs the verification that webhook would do
        // But synchronously (useful for testing, webhook fallback)
        
        Payment payment = paymentService.getById(paymentId);
        
        // Already captured?
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return ResponseEntity.ok(PaymentMapper.toDTO(payment));
        }
        
        // Check with Razorpay
        try {
            RazorpayPayment razorpayPayment = razorpayClient.getPayment(
                payment.getRazorpayPaymentId()
            );
            
            if ("captured".equals(razorpayPayment.getStatus())) {
                // Update our record
                payment.setStatus(PaymentStatus.CAPTURED);
                payment.setCapturedAt(Instant.now());
                paymentService.save(payment);
                
                // Publish event
                paymentService.publishPaymentCapturedEvent(payment);
                
                return ResponseEntity.ok(PaymentMapper.toDTO(payment));
            } else {
                return ResponseEntity.ok(PaymentMapper.toDTO(payment));  // Still pending
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .build();
        }
    }
}
```

### 6. Database Schema

```sql
-- V1.0__Create_Payments_Table.sql

CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    
    invoice_id BIGINT NOT NULL,  -- Foreign key to invoices
    
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    
    -- Payment status
    status VARCHAR(20) NOT NULL,  -- PENDING, CAPTURED, FAILED
    
    -- Razorpay identifiers
    razorpay_order_id VARCHAR(50),
    razorpay_payment_id VARCHAR(50),
    razorpay_signature VARCHAR(128),
    
    -- Timestamps
    captured_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    
    -- Indexes
    UNIQUE (razorpay_order_id),          -- One order per payment
    INDEX idx_invoice (invoice_id),      -- Query by invoice
    INDEX idx_status (status),           -- Find pending payments
    INDEX idx_razorpay_payment (razorpay_payment_id)
);

-- Audit log
CREATE TABLE payment_audit (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    action VARCHAR(50),  -- CREATED, CAPTURED, FAILED
    old_status VARCHAR(20),
    new_status VARCHAR(20),
    details JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## Security Considerations

### 1. Webhook Signature Verification

✅ **HMAC-SHA256 Verification**
- Every webhook verified against shared secret
- Prevents forged webhooks
- Implemented with constant-time comparison (prevents timing attacks)

### 2. Webhook Idempotency

✅ **Status Check**
```java
if (payment.getStatus() == PaymentStatus.CAPTURED) {
    return;  // Don't process duplicate
}
```

### 3. Replay Attack Prevention

✅ **Unique Order ID**
- Each payment has unique `razorpayOrderId`
- Database constraint prevents duplicate orders
- Old webhook for same order ignored (already CAPTURED)

### 4. PCI Compliance

✅ **No Card Storage**
- Card details never sent to our server
- Razorpay handles card data (PCI-DSS compliant)
- We only store payment references (order_id, payment_id)

### 5. Amount Validation

```java
// Verify amount matches invoice
if (!razorpayPayment.getAmount().equals(payment.getAmount() * 100)) {
    throw new PaymentException("Amount mismatch");
}
```

---

## Webhook Delivery Guarantees

### Razorpay Webhook Retry Logic

Razorpay retries webhook delivery:
- **Attempt 1:** Immediately
- **Attempt 2:** After 1 minute
- **Attempt 3:** After 5 minutes
- **Attempt 4:** After 30 minutes
- **Attempt 5:** After 2 hours

**Our Side:** Idempotent handler means multiple attempts safe ✅

---

## Testing

### Test: Webhook Verification

```java
@Test
void validWebhookSignatureAccepted() {
    String body = """
        {
            "event": "payment.authorized",
            "payload": { "payment": { "id": "pay_123", "status": "captured" } }
        }
    """;
    
    // Compute correct signature
    String signature = computeHmac(body, webhookSecret);
    
    RazorpayWebhookRequest request = new RazorpayWebhookRequest(body);
    request.setHeader("X-Razorpay-Signature", signature);
    
    // Should accept
    boolean isValid = webhookVerifier.verify(request);
    assertThat(isValid).isTrue();
}

@Test
void invalidWebhookSignatureRejected() {
    String body = "...";
    String invalidSignature = "tampered_signature";
    
    RazorpayWebhookRequest request = new RazorpayWebhookRequest(body);
    request.setHeader("X-Razorpay-Signature", invalidSignature);
    
    // Should reject
    boolean isValid = webhookVerifier.verify(request);
    assertThat(isValid).isFalse();
}
```

### Test: Webhook Idempotency

```java
@Test
void duplicateWebhookProcessedOnce() {
    // Send webhook 1
    webhookHandler.handlePaymentWebhook(request1);
    
    Payment payment1 = paymentRepository.findById(paymentId).orElseThrow();
    assertThat(payment1.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    
    // Send identical webhook again (duplicate)
    webhookHandler.handlePaymentWebhook(request1);
    
    Payment payment2 = paymentRepository.findById(paymentId).orElseThrow();
    assertThat(payment2.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    
    // Should still have same capture time (not updated twice)
    assertThat(payment1.getCapturedAt()).isEqualTo(payment2.getCapturedAt());
}
```

---

## Monitoring & Alerting

### Metrics

| Metric | Alert | Purpose |
|--------|-------|---------|
| **Webhook Success Rate** | < 99% | Payment processing reliability |
| **Webhook Latency** | p99 > 5s | User experience |
| **Failed Payments** | > threshold | Customer impact |
| **Signature Failures** | > 0 | Security incidents |

### Example Alert

```yaml
alert: WebhookSignatureFailure
expr: rate(webhook_signature_failures_total[5m]) > 0
annotations:
  summary: "Webhook signature verification failures detected"
  action: "Review webhook secret configuration"
```

---

## Comparison with Alternatives

| Aspect | Polling Only | Webhook Only | 2-Step (Selected) |
|--------|-----------|-------------|------------------|
| **Speed** | Slow (5s+) | Fast (< 1s) | Fast |
| **Reliable** | No (API fails) | No (webhook lost) | Yes |
| **Secure** | Yes | If verified | Yes |
| **Scale** | No (too many calls) | Yes | Yes |
| **Complexity** | Simple | Medium | Medium |
| **Selected** | ❌ | ❌ | ✅ |

---

## Related ADRs

- [ADR-001: Schema-Per-Tenant](./ADR-001-schema-per-tenant.md)
- [ADR-002: Outbox Pattern](./ADR-002-outbox-pattern.md)

---

## References

- Razorpay Webhooks: https://razorpay.com/docs/webhooks/
- HMAC Security: https://tools.ietf.org/html/rfc2104
- PCI-DSS Compliance: https://www.pcisecuritystandards.org/
- Idempotency in Payments: https://stripe.com/docs/idempotency
