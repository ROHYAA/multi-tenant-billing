---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - webhook-security
  - hmac-verification
  - signature-validation
  - idempotency
  - razorpay-webhooks
  - replay-prevention
  - event-ordering
related_documents:
  - ./payment-processing.md
  - ./configuration-reference.md
  - ./observability.md
---

# Webhook Security: HMAC Verification, Idempotency, & Replay Prevention

## Executive Summary

**Webhooks** are asynchronous HTTP callbacks from Razorpay when events occur (payment captured, refund issued, etc.). MTBS validates every webhook using **HMAC-SHA256 signatures** to prove authenticity and prevent spoofing.

This document explains:
1. **HMAC Signature Verification** — verify webhook came from Razorpay
2. **Idempotency Keys** — prevent double-processing if webhook retried
3. **Event Deduplication** — handle out-of-order, duplicate, or stale events
4. **Replay Prevention** — detect replay attacks
5. **IP Whitelisting** — restrict webhook source to Razorpay IPs

Key insight: Webhooks are **untrusted input**. Never trust a webhook without HMAC validation.

---

## Context / Problem

### Historical Problem: Webhook Spoofing

**Attack Scenario:**

```
Attacker intercepts webhook endpoint (public API)
POST /webhooks/razorpay
{
  "event": "payment.captured",
  "payload": {
    "payment": {
      "id": "pay_xyz",
      "invoice_id": "inv_999",
      "amount": 299967
    }
  }
}

No signature verification → MTBS trusts webhook
→ Activates subscription upgrade for attacker's subscription
→ Attacker gets PRO features without paying
```

**Solution: HMAC Signature Verification**

Razorpay signs every webhook with a shared secret known only to Razorpay and MTBS:

```
Webhook body = '{"event": "payment.captured", "payload": {...}}'
Secret = "topsecret123"
Signature = HMAC-SHA256(body, secret) = "abc123def456..."

Razorpay sends:
POST /webhooks/razorpay
X-Razorpay-Signature: abc123def456...
{webhook body}

MTBS validates:
1. Compute: HMAC-SHA256(received_body, secret) = "abc123def456..."
2. Compare: received_signature == computed_signature
3. If match: Trust webhook. If mismatch: Reject (spoofed).
```

---

## Dependencies

### Inbound (What depends on webhook security)
- **Payment Processing** — Webhook triggers subscription upgrade, invoice payment marking
- **Notification System** — Sends confirmation emails based on webhook events
- **Accounting** — Records transactions in audit logs

### Outbound (What webhook security depends on)
- **Razorpay API** — Provides webhook secret for HMAC signing
- **Redis** — Stores idempotency keys to detect duplicates
- **Database** — Stores webhook events for audit trail
- **Configuration** — Webhook secret injected via environment variable

### Configuration
```yaml
razorpay:
  webhook:
    secret: ${RAZORPAY_WEBHOOK_SECRET}
    path: /api/webhooks/razorpay
    
    # Security settings
    require-signature: true
    require-ip-whitelist: true
    ip-whitelist:
      - 13.126.86.0/24
      - 13.127.232.0/21
    
    # Event handling
    timeout-seconds: 30
    retry-count: 3
    
    # Idempotency
    idempotency-ttl-hours: 24
    duplicate-detection: true
```

---

## Design / Implementation

### Layer 1: HMAC Signature Verification

#### Algorithm: HMAC-SHA256

```java
@Component
public class RazorpaySignatureVerifier {
    
    private final String webhookSecret;
    
    public boolean verifySignature(String body, String receivedSignature) {
        try {
            // Compute expected signature
            String expectedSignature = computeHmacSha256(body, webhookSecret);
            
            // Compare using constant-time equality (prevent timing attacks)
            return constantTimeEquals(
                receivedSignature.getBytes(),
                expectedSignature.getBytes());
                
        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }
    
    private String computeHmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
            secret.getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"));
        
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        
        // Convert to hex string
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
    
    // Constant-time comparison (prevent timing attacks)
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
```

**Why Constant-Time Comparison?**

```
Timing Attack (vulnerable):
String a = "abc123...";
String b = "xyz789...";

// Java's equals() returns early on first mismatch
if (a.equals(b)) {  // 'a' != 'xyz'? Return false immediately (1ms)
    return true;
}

Attacker's attack:
Signature = "a" + random
→ Server compares, returns early if doesn't match 'a'
→ Attacker measures latency: 1ms (no match) vs 50ms (partial match)
→ Attacker brute-forces signature one character at a time

Constant-time comparison:
Loop through all bytes, XOR them, compare result
→ Always takes same time regardless of where mismatch occurs
→ Timing attack useless
```

#### Webhook Controller

```java
@RestController
@RequestMapping("/api/webhooks/razorpay")
@Slf4j
public class RazorpayWebhookController {
    
    private final RazorpaySignatureVerifier signatureVerifier;
    private final WebhookEventService webhookEventService;
    private final RazorpayIpValidator ipValidator;
    
    @PostMapping
    public ResponseEntity<WebhookResponse> handleWebhook(
        @RequestBody String body,
        @RequestHeader("X-Razorpay-Signature") String signature,
        HttpServletRequest request) {
        
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        
        try {
            // Step 1: Verify source IP
            String clientIp = getClientIp(request);
            if (!ipValidator.isAllowed(clientIp)) {
                log.warn("Webhook rejected: disallowed IP {}", clientIp);
                return ResponseEntity.status(403)
                    .body(new WebhookResponse(false, "IP not whitelisted"));
            }
            
            // Step 2: Verify HMAC signature
            if (!signatureVerifier.verifySignature(body, signature)) {
                log.error("Webhook rejected: signature verification failed");
                return ResponseEntity.status(401)
                    .body(new WebhookResponse(false, "Signature invalid"));
            }
            
            log.info("Webhook signature verified");
            
            // Step 3: Parse JSON
            WebhookPayload payload = parseWebhookJson(body);
            
            // Step 4: Process event (async, with idempotency)
            webhookEventService.processEvent(payload, correlationId);
            
            return ResponseEntity.ok(new WebhookResponse(true, "Received"));
            
        } catch (JsonParseException e) {
            log.error("Webhook rejected: invalid JSON: {}", e.getMessage());
            return ResponseEntity.status(400)
                .body(new WebhookResponse(false, "Invalid JSON"));
        } catch (Exception e) {
            log.error("Webhook processing error: {}", e.getMessage());
            return ResponseEntity.status(500)
                .body(new WebhookResponse(false, "Processing error"));
        } finally {
            MDC.clear();
        }
    }
    
    private String getClientIp(HttpServletRequest request) {
        // Check X-Forwarded-For (if behind proxy)
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

**HTTP Response Codes:**

| Code | Meaning | Razorpay Behavior |
|------|---------|-------------------|
| 200 OK | Accepted | Stops retrying |
| 4xx Client Error | Invalid request | Stops retrying (no point retrying) |
| 5xx Server Error | Temporary failure | Retries (exponential backoff) |

---

### Layer 2: IP Whitelisting

#### Razorpay IP Ranges (Static)

```java
@Component
public class RazorpayIpValidator {
    
    private final List<SubnetUtils.SubnetInfo> allowedSubnets;
    
    @PostConstruct
    public void init() {
        // Razorpay IP ranges (as of 2026)
        // Get latest from: https://razorpay.com/docs/webhooks/api/
        List<String> cidrs = List.of(
            "13.126.86.0/24",      // AWS region 1
            "13.127.232.0/21",     // AWS region 2
            "52.27.170.0/23",      // Backup range
            "52.27.172.0/23"       // Backup range
        );
        
        allowedSubnets = cidrs.stream()
            .map(SubnetUtils::new)
            .map(SubnetUtils::getInfo)
            .collect(toList());
    }
    
    public boolean isAllowed(String ipAddress) {
        return allowedSubnets.stream()
            .anyMatch(subnet -> subnet.isInRange(ipAddress));
    }
}
```

**Configuration:**

```yaml
razorpay:
  webhook:
    require-ip-whitelist: true
    ip-whitelist:
      - 13.126.86.0/24
      - 13.127.232.0/21
```

**Testing IP Whitelist:**

```bash
# Simulate webhook from allowed IP
curl -X POST http://localhost:8080/api/webhooks/razorpay \
  -H "X-Forwarded-For: 13.126.86.50" \
  -H "X-Razorpay-Signature: valid-signature" \
  -d '{"event": "payment.captured"}'
# Result: 200 OK

# Simulate webhook from disallowed IP (attacker)
curl -X POST http://localhost:8080/api/webhooks/razorpay \
  -H "X-Forwarded-For: 1.2.3.4" \
  -H "X-Razorpay-Signature: valid-signature" \
  -d '{"event": "payment.captured"}'
# Result: 403 Forbidden
```

---

### Layer 3: Idempotency & Deduplication

#### Problem: Webhook Retries Can Cause Duplicate Processing

**Scenario: Network glitch**

```
14:00:00.000 Razorpay sends webhook: payment.captured
14:00:00.100 MTBS receives, processes, activates subscription upgrade
14:00:00.200 MTBS sends 200 OK response

Network timeout (Razorpay doesn't receive 200 OK)

14:00:05.000 Razorpay retries: payment.captured (same event)
14:00:05.100 MTBS receives, processes, activates subscription upgrade AGAIN
           → Subscription upgraded twice (bug!)

Result: User charged twice, subscription state corrupted
```

#### Solution: Idempotency Key Tracking

```java
@Component
public class WebhookIdempotencyService {
    
    private final StringRedisTemplate redisTemplate;
    
    /**
     * Check if webhook already processed.
     * Returns the cached response if so.
     */
    public Optional<WebhookEvent> getProcessedEvent(String eventId) {
        String key = "webhook:event:" + eventId;
        String cached = redisTemplate.opsForValue().get(key);
        
        if (cached != null) {
            return Optional.of(parseEvent(cached));
        }
        return Optional.empty();
    }
    
    /**
     * Record webhook as processed.
     * TTL: 24 hours (Razorpay retries within 24 hours).
     */
    public void recordProcessed(WebhookEvent event) {
        String key = "webhook:event:" + event.getId();
        String value = serializeEvent(event);
        
        redisTemplate.opsForValue().set(
            key, value,
            Duration.ofHours(24));  // TTL
    }
}

@Service
public class WebhookEventService {
    
    private final WebhookIdempotencyService idempotencyService;
    private final PaymentService paymentService;
    private final SubscriptionService subscriptionService;
    
    public void processEvent(WebhookPayload payload, String correlationId) {
        String eventId = payload.getId();  // Unique webhook event ID
        
        // Check if already processed
        Optional<WebhookEvent> cached = idempotencyService.getProcessedEvent(eventId);
        if (cached.isPresent()) {
            log.info("Webhook already processed: eventId={}, skipping", eventId);
            // Idempotent: return cached result, don't reprocess
            return;
        }
        
        // First time seeing this event
        try {
            switch (payload.getEvent()) {
                case "payment.captured":
                    handlePaymentCaptured(payload);
                    break;
                case "payment.failed":
                    handlePaymentFailed(payload);
                    break;
                case "refund.created":
                    handleRefundCreated(payload);
                    break;
                // ... other event types
            }
            
            // Mark as processed
            WebhookEvent event = new WebhookEvent(eventId, payload.getEvent(), "SUCCESS");
            idempotencyService.recordProcessed(event);
            
        } catch (Exception e) {
            log.error("Webhook processing failed: {}", e.getMessage());
            
            // Record failure (don't retry if business logic error)
            WebhookEvent event = new WebhookEvent(eventId, payload.getEvent(), "FAILED");
            idempotencyService.recordProcessed(event);
            
            throw e;
        }
    }
    
    private void handlePaymentCaptured(WebhookPayload payload) {
        String invoiceId = payload.getPayload().getInvoice().getId();
        String razorpayPaymentId = payload.getPayload().getPayment().getId();
        
        // Call payment service
        PaymentVerificationResponse response = paymentService
            .verifyAndCapturePayment(invoiceId);
        
        log.info("Payment webhook processed: invoiceId={}, razorpayPaymentId={}",
            invoiceId, razorpayPaymentId);
    }
}
```

**Idempotency Key Flow:**

```
1. Webhook arrives: eventId = "event_ABC123"
2. Check Redis: key "webhook:event:event_ABC123" ?
   → Miss (first time)
   → Process event
   → Store result in Redis for 24 hours
3. Same webhook retried: eventId = "event_ABC123"
4. Check Redis: key "webhook:event:event_ABC123" ?
   → Hit! Event already processed
   → Return cached result (no processing)
   → Idempotent: same effect as first time
```

---

### Layer 4: Event Ordering & Deduplication

#### Problem: Out-of-Order Webhooks

**Scenario: Subscription upgrade**

```
14:00:00 Webhook 1: payment.captured (payment verified)
14:00:01 Webhook 2: order.paid (order marked paid)

Network delay:

14:00:00 Webhook 2: order.paid (arrives first!)
         → Try to mark order paid, but order doesn't exist yet (not created)
         → Error: "Order not found"

14:00:01 Webhook 1: payment.captured (arrives second)
         → Create order, mark as paid
         → Subscription upgraded

Result: State misalignment, potential bugs
```

#### Solution: Event Sequencing with Database

```java
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {
    @Id String id;
    @Column(nullable = false) String eventType;  // payment.captured, order.paid, etc.
    @Column(nullable = false) LocalDateTime occurredAt;  // When Razorpay says it happened
    @Column(columnDefinition = "TEXT") String payload;
    @Column(nullable = false) LocalDateTime receivedAt;  // When we received it
    @Column(nullable = false) WebhookStatus status;  // PENDING, PROCESSING, COMPLETED, FAILED
    @Column(nullable = false) Integer attemptCount;  // Retry count
    @Column(name = "source_type") String sourceType;  // razorpay, stripe, etc.
}

@Service
public class WebhookEventService {
    
    public void processEvent(WebhookPayload payload) {
        // 1. Store webhook event in DB (immutable log)
        WebhookEvent event = new WebhookEvent();
        event.setId(payload.getId());
        event.setEventType(payload.getEvent());
        event.setOccurredAt(payload.getCreatedAt());  // Server time from Razorpay
        event.setPayload(serializePayload(payload));
        event.setReceivedAt(Instant.now());
        event.setStatus(WebhookStatus.PENDING);
        event.setAttemptCount(0);
        webhookEventRepository.save(event);
        
        // 2. Process asynchronously with ordering
        webhookProcessingQueue.enqueue(event);
    }
}

@Component
public class WebhookProcessor {
    
    @Scheduled(fixedRate = 1000)  // Process every 1 second
    public void processQueuedEvents() {
        // Get pending events ordered by occurredAt (sequence)
        List<WebhookEvent> pending = webhookEventRepository
            .findByStatusOrderByOccurredAtAsc(WebhookStatus.PENDING);
        
        for (WebhookEvent event : pending) {
            try {
                event.setStatus(WebhookStatus.PROCESSING);
                webhookEventRepository.save(event);
                
                // Process event
                handleEvent(event);
                
                event.setStatus(WebhookStatus.COMPLETED);
                webhookEventRepository.save(event);
                
            } catch (Exception e) {
                event.setAttemptCount(event.getAttemptCount() + 1);
                
                if (event.getAttemptCount() < 3) {
                    event.setStatus(WebhookStatus.PENDING);  // Retry
                } else {
                    event.setStatus(WebhookStatus.FAILED);  // Give up
                    alerting.alertWebhookFailure(event, e);
                }
                
                webhookEventRepository.save(event);
            }
        }
    }
}
```

**Benefits:**
- Webhooks processed in order (by `occurredAt`)
- Failures retried automatically
- Full audit trail (all webhooks logged)
- Deduplication (same eventId processed once)

---

### Layer 5: Webhook Testing

#### Test: Valid Signature

```java
@Test
public void testValidSignatureIsAccepted() {
    String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"id\":\"pay_xyz\"}}}";
    String secret = "test-secret";
    
    // Compute correct signature
    String signature = computeHmacSha256(body, secret);
    
    // Send webhook
    mvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signature)
        .header("X-Forwarded-For", "13.126.86.50")
        .contentType(APPLICATION_JSON)
        .content(body))
        .andExpect(status().isOk());
    
    // Verify payment service was called
    verify(paymentService).verifyAndCapturePayment(any());
}
```

#### Test: Invalid Signature

```java
@Test
public void testInvalidSignatureIsRejected() {
    String body = "{\"event\":\"payment.captured\"}";
    String invalidSignature = "not-the-real-signature";
    
    mvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", invalidSignature)
        .header("X-Forwarded-For", "13.126.86.50")
        .contentType(APPLICATION_JSON)
        .content(body))
        .andExpect(status().isUnauthorized());
    
    // Verify payment service was NOT called
    verify(paymentService, never()).verifyAndCapturePayment(any());
}
```

#### Test: Disallowed IP

```java
@Test
public void testDisallowedIpIsRejected() {
    String body = "{\"event\":\"payment.captured\"}";
    String signature = computeHmacSha256(body, secret);
    
    mvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signature)
        .header("X-Forwarded-For", "1.2.3.4")  // Attacker IP
        .contentType(APPLICATION_JSON)
        .content(body))
        .andExpect(status().isForbidden());
}
```

#### Test: Idempotency

```java
@Test
public void testDuplicateWebhookIsIdempotent() {
    String body = "{\"event\":\"payment.captured\"}";
    String signature = computeHmacSha256(body, secret);
    
    // First webhook
    mvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signature)
        .header("X-Forwarded-For", "13.126.86.50")
        .contentType(APPLICATION_JSON)
        .content(body))
        .andExpect(status().isOk());
    
    // Verify payment service called once
    verify(paymentService, times(1)).verifyAndCapturePayment(any());
    
    // Same webhook (retry)
    mvc.perform(post("/api/webhooks/razorpay")
        .header("X-Razorpay-Signature", signature)
        .header("X-Forwarded-For", "13.126.86.50")
        .contentType(APPLICATION_JSON)
        .content(body))
        .andExpect(status().isOk());
    
    // Still only called once (idempotent)
    verify(paymentService, times(1)).verifyAndCapturePayment(any());
}
```

---

## Known Issues / Limitations

1. **IP Range Changes** — Razorpay may change IP ranges. If not updated, webhooks rejected. Solution: Monitor Razorpay release notes, test in staging before prod update.

2. **Clock Skew** — Signature verified, but what if webhook `createdAt` is very old (attacker resending old webhook)? Could allow replay. Solution: Check `createdAt` is within 5 minutes of now.

3. **Redis Failure** — If Redis down, idempotency check fails. Webhooks re-processed. Solution: Use database for idempotency (slower but reliable).

4. **Webhook Lost** — If MTBS crashes before storing webhook in DB, Razorpay won't retry. Payment accepted but subscription not activated. Solution: Implement webhook recovery (query Razorpay API for missed payments).

5. **Event Ordering Assumption** — Assumes `occurredAt` provided by Razorpay is reliable. If Razorpay's clock is skewed, ordering may be wrong. Solution: Trust Razorpay's timestamps.

---

## Future Improvements

1. **Webhook Recovery** — Periodically query Razorpay API for recent payments not yet processed. Re-trigger local webhooks if missed.

2. **Multi-Provider Support** — Support Stripe, Google Play, Apple Store webhooks. Generalize signature verification logic.

3. **Webhook Replay Detection** — Track webhook timestamps, reject replays >1 minute old.

4. **Dead Letter Queue** — Failed webhooks go to DLQ for manual investigation.

5. **Webhook Rate Limiting** — Protect against webhook flood (e.g., Razorpay compromised). Limit to N webhooks per minute.

---

## Related Documents

- [payment-processing.md](./payment-processing.md) — Payment flow that webhooks trigger
- [observability.md](./observability.md) — Logging webhook events with correlation IDs
- [configuration-reference.md](./configuration-reference.md) — Webhook configuration (IP whitelist, timeout)
