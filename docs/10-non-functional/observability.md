---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - observability
  - logging
  - metrics
  - tracing
  - distributed-tracing
  - alerting
  - monitoring
  - structured-logs
  - micrometer
  - sleuth
related_documents:
  - ../ENTERPRISE_REFACTORING_SUMMARY.md
  - ./notification-module.md
  - ./payment-processing.md
---

# Observability: Logging, Metrics, Tracing, & Alerting

## Executive Summary

**Observability** is the ability to understand system behavior from external outputs without inspecting internals. MTBS achieves observability through three pillars:

1. **Structured Logging** — JSON logs with `tenantId`, `userId`, `correlationId`, `operationName` for querying and alerting
2. **Metrics** — Micrometer counters & timers (request latency, payment failures, subscription upgrades)
3. **Distributed Tracing** — Spring Cloud Sleuth + Zipkin traces requests across microservices (planned for v2)

This document explains the logging strategy, metric definitions, alert thresholds, and correlation ID propagation patterns. It covers real examples from payment processing, notification delivery, and scheduler jobs.

---

## Context / Problem

### Historical Problem: Silent Failures

**Before observability:**
```
Payment upgrade fails silently. Support ticket: "User says he paid but didn't upgrade."
Search logs: grep "invoiceId=123" → 500 results from 6 services
Manually correlate: payment service log, subscription service log, notification log
Took 2 hours to find: SMTP timeout in email notification blocked entire workflow.
```

**After observability:**
```
Same failure, but correlated logs with correlationId=abc-123:
[2026-05-17 14:30:15] WARN [correlationId=abc-123] [tenantId=t1] 
  PaymentService: verifyAndCapturePayment succeeded (₹2799.67)
[2026-05-17 14:30:16] INFO [correlationId=abc-123] [tenantId=t1]
  SubscriptionService: activateUpgradeAfterPayment in progress
[2026-05-17 14:30:16] ERROR [correlationId=abc-123] [tenantId=t1]
  NotificationService: SMTP timeout sending PLAN_UPGRADED email, queued for retry

All logs appear in one search. Root cause found in 30 seconds.
```

### Why Structured Logs Matter

**Unstructured (bad):**
```
2026-05-17 14:30:15 Payment processed for user in tenant X with id Y amount 2799.67
```
- Cannot parse programmatically
- Grep alone finds false positives (other tenants, other payments)
- No timestamp precision

**Structured (good):**
```json
{
  "timestamp": "2026-05-17T14:30:15.123Z",
  "level": "INFO",
  "logger": "com.mtbs.billing.service.PaymentService",
  "message": "Payment captured successfully",
  "correlationId": "abc-123",
  "tenantId": "t1",
  "userId": "u42",
  "invoiceId": "inv_999",
  "amount": 279967,
  "currency": "INR",
  "razorpayPaymentId": "pay_xyz",
  "durationMs": 145
}
```
- Queryable: `correlationId=abc-123 AND tenantId=t1`
- No false positives
- Parseable by log aggregation (Splunk, Datadog, CloudWatch)

---

## Dependencies

### Inbound (What uses observability)
- **Operations Team** — queries logs to debug production issues
- **Monitoring Stack** — Prometheus scrapes metrics, triggers alerts
- **Tracing UI** — Zipkin/Jaeger visualizes request flow (future)

### Outbound (What observability uses)
- **Spring Cloud Sleuth** — MDC propagation (correlationId, tenantId)
- **SLF4J** — Logging facade
- **Logback** — Log formatting and routing
- **Micrometer** — Metrics collection
- **Prometheus** — Metrics storage & scraping (future)
- **CloudWatch / Splunk / Datadog** — Log aggregation (customer-specific)

### Configuration
```yaml
logging:
  level:
    root: INFO
    com.mtbs: DEBUG
  pattern:
    console: "%d{ISO8601} %highlight(%-5level) [%X{tenantId}] [%X{correlationId}] %logger{36} : %msg%n"
    file: "%d{ISO8601} %-5level [%X{tenantId}] [%X{correlationId}] %logger{36} : %msg%n"
  file:
    name: logs/mtbs.log
    max-size: 100MB
    max-history: 30

management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## Design / Implementation

### Layer 1: Structured Logging

#### Log Context Propagation

**Entry Point (ControllerAdvice):**
```java
@Component
public class RequestLoggingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletRequest http = (HttpServletRequest) req;
        
        // Extract or generate correlationId
        String correlationId = http.getHeader("X-Correlation-ID");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        
        // Extract tenantId from JWT or request header
        String tenantId = extractTenantIdFromContext();
        
        // Set MDC (Mapped Diagnostic Context)
        MDC.put("correlationId", correlationId);
        MDC.put("tenantId", tenantId);
        MDC.put("userId", extractUserIdFromContext());
        MDC.put("method", http.getMethod());
        MDC.put("path", http.getRequestURI());
        
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

**Propagation Through Services:**
```java
@Service
@Slf4j
public class PaymentService {
    public PaymentVerificationResponse verifyAndCapturePayment(String invoiceId) {
        // correlationId, tenantId already in MDC from filter
        // logger includes them automatically in %X{correlationId}
        
        log.info("Starting payment verification", 
            Map.of("invoiceId", invoiceId, "stage", "verify"));
        
        Invoice invoice = invoiceService.getInvoice(invoiceId);
        
        boolean signatureValid = verifyRazorpaySignature(invoice.razorpayOrderId);
        
        if (!signatureValid) {
            log.error("Razorpay signature verification failed",
                Map.of("invoiceId", invoiceId, "reason", "HMAC mismatch"));
            throw new PaymentException("Signature invalid");
        }
        
        // Signature valid, capture payment
        log.info("Razorpay signature verified, capturing payment",
            Map.of("invoiceId", invoiceId, "amount", invoice.amount));
        
        // Simulate capture
        Thread.sleep(150);
        
        log.info("Payment captured successfully",
            Map.of("invoiceId", invoiceId, "amount", invoice.amount, 
                   "razorpayPaymentId", invoice.razorpayPaymentId));
        
        // Trigger subscription upgrade
        subscriptionService.activateUpgradeAfterPayment(invoiceId);
        
        return new PaymentVerificationResponse(/* ... */);
    }
}
```

**Output (JSON format via Logback encoder):**
```json
{
  "@timestamp": "2026-05-17T14:30:15.123Z",
  "level": "INFO",
  "logger_name": "com.mtbs.billing.service.PaymentService",
  "message": "Payment captured successfully",
  "tenantId": "t1",
  "correlationId": "abc-123",
  "userId": "u42",
  "invoiceId": "inv_999",
  "amount": 279967,
  "razorpayPaymentId": "pay_xyz",
  "thread_name": "http-nio-8080-exec-10"
}
```

#### Log Levels & When to Use

| Level | When | Example |
|-------|------|---------|
| **ERROR** | Unexpected failure, user impacted, alerts on-call | SMTP timeout, payment declined, DB connection lost |
| **WARN** | Unexpected but handled gracefully, no immediate impact | Razorpay order creation took 5s (slow), max retries reached but fallback triggered |
| **INFO** | Important business events | Subscription created, plan upgraded, notification sent, payment verified |
| **DEBUG** | Detailed flow for debugging | Entering method, resolved parameter value, cache hit/miss |
| **TRACE** | Extremely verbose, disabled in production | Every loop iteration, every object field value |

**Production Config:**
```yaml
logging:
  level:
    root: WARN
    com.mtbs: INFO  # Business logic = INFO
    com.mtbs.billing: DEBUG  # High-sensitivity module = DEBUG
    org.springframework.web: WARN
    org.springframework.security: WARN
```

### Layer 2: Metrics

#### Key Metrics (Micrometer)

**Request Latency (Histogram):**
```java
@Component
@Aspect
public class MetricsAspect {
    private final MeterRegistry meterRegistry;
    
    @Around("@annotation(com.mtbs.shared.Metrics)")
    public Object measureLatency(ProceedingJoinPoint pjp) throws Throwable {
        String methodName = pjp.getSignature().getName();
        String className = pjp.getSignature().getDeclaringType().getSimpleName();
        
        Timer timer = Timer.builder("method.execution")
            .tag("class", className)
            .tag("method", methodName)
            .register(meterRegistry);
        
        return timer.recordCallable(() -> {
            try {
                return pjp.proceed();
            } catch (Throwable e) {
                Counter.builder("method.errors")
                    .tag("class", className)
                    .tag("method", methodName)
                    .tag("exception", e.getClass().getSimpleName())
                    .register(meterRegistry)
                    .increment();
                throw e;
            }
        });
    }
}
```

**Payment Metrics:**
```java
@Service
@Slf4j
public class PaymentService {
    private final MeterRegistry meterRegistry;
    
    public PaymentVerificationResponse verifyAndCapturePayment(String invoiceId) {
        // Track successful verifications
        meterRegistry.counter("payment.verifications.success").increment();
        
        // Track amount processed
        meterRegistry.counter("payment.amount.processed", 
            "currency", "INR").increment(invoice.getAmount() / 100.0);
        
        // Track capture latency
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            razorpayApi.capturePayment(/* ... */);
            sample.stop(Timer.builder("payment.capture.latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
        } catch (RazorpayException e) {
            meterRegistry.counter("payment.capture.failures",
                "reason", e.getErrorCode()).increment();
            throw e;
        }
    }
}
```

**Subscription Metrics:**
```java
@Service
public class SubscriptionService {
    private final MeterRegistry meterRegistry;
    
    public SubscriptionResponse initiateUpgradeToPro(UpgradeRequest request) {
        // Track upgrade initiated
        meterRegistry.counter("subscription.upgrade.initiated",
            "fromPlan", current.getPlanId(),
            "toPlan", "pro").increment();
        
        // Track upgrade value
        meterRegistry.counter("subscription.upgrade.value",
            "currency", "INR")
            .increment(chargeAmount / 100.0);
        
        // Later, when payment succeeds:
        meterRegistry.counter("subscription.upgrade.completed",
            "fromPlan", current.getPlanId(),
            "toPlan", "pro").increment();
    }
}
```

**Notification Delivery Metrics:**
```java
@Service
public class NotificationService {
    private final MeterRegistry meterRegistry;
    
    public void sendNotification(DomainEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            notificationOrchestrator.orchestrate(event);
            sample.stop(Timer.builder("notification.delivery.latency")
                .tag("type", event.getClass().getSimpleName())
                .tag("status", "success")
                .register(meterRegistry));
                
            meterRegistry.counter("notification.delivered",
                "type", event.getClass().getSimpleName()).increment();
        } catch (Exception e) {
            sample.stop(Timer.builder("notification.delivery.latency")
                .tag("type", event.getClass().getSimpleName())
                .tag("status", "failure")
                .register(meterRegistry));
                
            meterRegistry.counter("notification.failed",
                "type", event.getClass().getSimpleName(),
                "reason", e.getClass().getSimpleName()).increment();
        }
    }
}
```

#### Prometheus Endpoints

**Metrics Endpoint:**
```
GET http://localhost:8080/actuator/prometheus

Response:
# HELP payment_verifications_success_total Total payment verifications
# TYPE payment_verifications_success_total counter
payment_verifications_success_total{tenantId="t1"} 42.0
payment_verifications_success_total{tenantId="t2"} 18.0

# HELP payment_capture_latency_seconds Payment capture latency
# TYPE payment_capture_latency_seconds histogram
payment_capture_latency_seconds_bucket{le="0.01",tenantId="t1"} 2.0
payment_capture_latency_seconds_bucket{le="0.05",tenantId="t1"} 8.0
payment_capture_latency_seconds_bucket{le="0.1",tenantId="t1"} 12.0
payment_capture_latency_seconds_bucket{le="0.5",tenantId="t1"} 38.0
payment_capture_latency_seconds_bucket{le="+Inf",tenantId="t1"} 42.0
```

---

### Layer 3: Distributed Tracing (Spring Cloud Sleuth)

#### Trace Propagation

**Current State (v1):** Single-process. Sleuth adds `X-B3-TraceId` / `X-B3-SpanId` headers for logging in one JVM.

**Future (v2):** Multiple services. Trace follows request across Auth Service → Billing Service → Notification Service.

**Configuration:**
```yaml
spring:
  cloud:
    sleuth:
      trace-id-128-bit: true  # 128-bit trace ID (vs 64-bit)
      sampler:
        probability: 0.1  # Sample 10% of traces (reduce costs)
  zipkin:
    base-url: http://zipkin:9411
    enabled: true
```

**Trace Example (Payment Upgrade Flow):**
```
Trace ID: 550e8400e29b41d4a716446655440000

Span 1: SubscriptionController.initiateUpgradeToPro
  └─ Span 2: SubscriptionService.previewUpgrade
     └─ Span 3: ProrationService.buildPreview
     └─ Span 4: PlanService.getPlan
     └─ Span 5: PlanService.getPriceMonthly
  └─ Span 6: InvoiceService.createInvoice
     └─ Span 7: RazorpayApi.createOrder
  └─ Span 8: SubscriptionService.updatePendingUpgrade
     └─ Span 9: SubscriptionRepository.save

Zipkin UI shows:
- Total latency: 342ms
- RazorpayApi.createOrder: 145ms (slow!)
- SubscriptionRepository.save: 12ms (fast)
- Bottleneck identified: Razorpay API call
```

---

### Layer 4: Alerting

#### Alert Rules (Prometheus)

**Define Alerts in prometheus.yml:**
```yaml
groups:
  - name: mtbs_billing
    rules:
      # Payment failures spike
      - alert: PaymentFailureRateHigh
        expr: rate(payment_capture_failures_total[5m]) > 0.05
        for: 1m
        annotations:
          summary: "Payment failure rate > 5% (5-min window)"
          description: "Payment captures failing at {{ $value | humanizePercentage }}"
          severity: critical

      # Notification delivery latency
      - alert: NotificationDeliveryLatencyHigh
        expr: histogram_quantile(0.95, notification_delivery_latency_seconds) > 30
        for: 5m
        annotations:
          summary: "Notification p95 latency > 30s"
          description: "p95 latency: {{ $value }}s (target: <30s)"
          severity: warning

      # Subscription upgrade success rate
      - alert: UpgradeCompletionRateLow
        expr: |
          rate(subscription_upgrade_completed_total[1h]) /
          rate(subscription_upgrade_initiated_total[1h]) < 0.8
        for: 5m
        annotations:
          summary: "Upgrade completion rate < 80%"
          description: "Only {{ $value | humanizePercentage }} upgrades completing"
          severity: warning

      # Error rate spike
      - alert: ErrorRateSpike
        expr: rate(method_errors_total[1m]) > 0.01
        for: 2m
        annotations:
          summary: "Error rate spike (>1% errors per minute)"
          description: "{{ $value | humanizePercentage }} errors in last minute"
          severity: critical

      # Database connection pool exhaustion
      - alert: DBConnectionPoolExhaustion
        expr: hikaricp_connections_active / hikaricp_connections > 0.9
        for: 1m
        annotations:
          summary: "DB connection pool >90% utilized"
          description: "{{ $value | humanizePercentage }} of pool in use"
          severity: critical

      # Scheduled job failure
      - alert: ScheduledJobFailure
        expr: rate(scheduled_job_errors_total[5m]) > 0
        for: 1m
        annotations:
          summary: "Scheduled job failing"
          description: "Job: {{ $labels.job_name }}, Error: {{ $labels.reason }}"
          severity: critical
```

**Alertmanager Configuration (notification routing):**
```yaml
alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - localhost:9093

# alertmanager.yml
global:
  resolve_timeout: 5m

route:
  receiver: 'default'
  routes:
    - match:
        severity: critical
      receiver: 'on-call-pagerduty'
      group_wait: 30s
      repeat_interval: 1h
    
    - match:
        severity: warning
      receiver: 'slack-billing-team'
      group_wait: 5m
      repeat_interval: 4h

receivers:
  - name: 'on-call-pagerduty'
    pagerduty_configs:
      - service_key: 'YOUR_PAGERDUTY_SERVICE_KEY'

  - name: 'slack-billing-team'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/...'
        channel: '#billing-alerts'
```

**Alert Runbooks:**

| Alert | Meaning | Action | Prevention |
|-------|---------|--------|-----------|
| PaymentFailureRateHigh | >5% payment captures failing in 5 min window | 1. Check Razorpay status page. 2. Check HMAC key rotation. 3. Check network connectivity. 4. Escalate to Razorpay support. | Use Razorpay mock in staging. Load test payment flows. |
| NotificationDeliveryLatencyHigh | Emails taking >30s to send (p95) | 1. Check SMTP server: `telnet smtp.gmail.com 587`. 2. Check queue size: query notification_queued_total metric. 3. Check network latency to SMTP. 4. Scale notification workers if queue growing. | Add SMTP connection pooling. Implement queue monitoring. Use async SMTP. |
| UpgradeCompletionRateLow | Only 80% of initiated upgrades completing (missing Step 2) | 1. Check webhook delivery: are payment.captured events firing? 2. Check logs for "activateUpgradeAfterPayment failed". 3. Verify Razorpay webhook HMAC. 4. Check notification service (blocking payment flow?). | Test full upgrade workflow in staging. Monitor webhook delivery SLA. |
| ErrorRateSpike | >1% error rate per minute (any service) | 1. Check recent code deployment (rollback if needed). 2. Check error logs: grep ERROR logs for patterns. 3. Check resource exhaustion: CPU, memory, DB connections. 4. Isolate affected service/endpoint. | Implement canary deployments. Monitor error rate by endpoint. |
| DBConnectionPoolExhaustion | Database connection pool >90% utilized | 1. Check slow queries: query `pg_stat_statements`. 2. Increase HikariCP pool size (default 10, try 20). 3. Check for connection leaks: close all statements/connections. 4. Restart affected service to reset pool. | Configure connection timeout. Monitor queries. Profile under load. |
| ScheduledJobFailure | Quartz job failing (e.g., downgrade-at-period-end) | 1. Check job logs for exception. 2. Check if job was paused: query quartz DB. 3. Manually re-run job if data loss unacceptable. 4. Escalate to support if recurring. | Set job to auto-retry. Implement job-level alerting. Monitor missed executions. |

---

## Code Flow: End-to-End Observability

### Scenario: Payment Verification with Observability

```
1. Client sends:
   POST /api/payments/verify
   X-Correlation-ID: abc-123
   Authorization: Bearer <JWT>

2. RequestLoggingFilter intercepts:
   [2026-05-17 14:30:15] INFO  RequestFilter: 
   MDC.put("correlationId", "abc-123")
   MDC.put("tenantId", "t1")
   MDC.put("userId", "u42")
   MDC.put("method", "POST")
   MDC.put("path", "/api/payments/verify")

3. PaymentController logs entry:
   [2026-05-17 14:30:15] DEBUG [correlationId=abc-123] [tenantId=t1]
   PaymentController: POST /verify called, invoiceId=inv_999

4. PaymentService logs step-by-step:
   [2026-05-17 14:30:15] INFO [correlationId=abc-123] [tenantId=t1]
   PaymentService: Fetching invoice inv_999
   
   [2026-05-17 14:30:15] INFO [correlationId=abc-123] [tenantId=t1]
   PaymentService: Verifying Razorpay signature
   
   [2026-05-17 14:30:15] INFO [correlationId=abc-123] [tenantId=t1]
   PaymentService: Signature verified, capturing payment
   
   [2026-05-17 14:30:16] INFO [correlationId=abc-123] [tenantId=t1]
   PaymentService: Payment captured, invoking subscription upgrade
   
5. SubscriptionService logs:
   [2026-05-17 14:30:16] INFO [correlationId=abc-123] [tenantId=t1]
   SubscriptionService: Activating upgrade after payment, invoiceId=inv_999
   
   [2026-05-17 14:30:16] INFO [correlationId=abc-123] [tenantId=t1]
   SubscriptionService: Subscription upgrade completed, planId=2 (PRO)

6. Metrics recorded:
   payment_verifications_success_total{tenantId="t1"} += 1
   payment_capture_latency_seconds.record(1.23, tags={success})
   subscription_upgrade_completed_total{fromPlan="free", toPlan="pro"} += 1

7. Event published (async):
   [2026-05-17 14:30:16] DEBUG [correlationId=abc-123] [tenantId=t1]
   DomainEventPublisher: Publishing PLAN_UPGRADED event
   
   NotificationEventListener async processes:
   [2026-05-17 14:30:18] INFO [correlationId=abc-123] [tenantId=t1]
   NotificationService: Sending PLAN_UPGRADED notification via email
   
   [2026-05-17 14:30:18] INFO [correlationId=abc-123] [tenantId=t1]
   SmtpEmailProvider: Email sent to user@example.com
   
   notification_delivered{type="PlanUpgradedEvent"} += 1

8. All logs queryable in log aggregation:
   Query: correlationId:abc-123 AND tenantId:t1
   → Returns 12 log entries showing complete flow
   → p95 latency: 3.23 seconds
   → All steps succeeded (no errors)
```

---

## Correlation ID Strategy

### Generation & Propagation

**HTTP Request Entry Point:**
```java
String correlationId = httpRequest.getHeader("X-Correlation-ID");
if (correlationId == null || correlationId.isEmpty()) {
    correlationId = UUID.randomUUID().toString();
}
MDC.put("correlationId", correlationId);
```

**Response Header (propagate back to client):**
```java
response.addHeader("X-Correlation-ID", MDC.get("correlationId"));
```

**Async Event Publishing (preserve correlationId):**
```java
ApplicationEvent event = new DomainEventPublisher.Event(domainEvent);
// MDC context auto-inherited by @EventListener in same thread

// If cross-thread (e.g., async via Executor):
public class AsyncConfig implements AsyncConfigurer {
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(task -> {
            // Capture current MDC
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                // Restore MDC in async thread
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        return executor;
    }
}
```

**External Service Calls (HTTP headers):**
```java
HttpHeaders headers = new HttpHeaders();
headers.set("X-Correlation-ID", MDC.get("correlationId"));
// RestTemplate or WebClient sends this header

// Receiving service extracts:
@GetMapping("/webhook")
public void handleWebhook(@RequestHeader("X-Correlation-ID") String correlationId) {
    MDC.put("correlationId", correlationId);
    // All logs in this method include correlationId
}
```

---

## Known Issues / Limitations

1. **Sampling: Only 10% of Traces** — Zipkin sampling set to 0.1 to reduce storage costs. Miss 90% of traces, including edge cases. Trade-off: cost vs detail.

2. **MDC Performance Overhead** — String operations in MDC.put() add ~1-2ms per request. Negligible but measurable at 10k req/sec.

3. **Sensitive Data in Logs** — Passwords, API keys, credit card tokens never logged, but full request/response bodies may contain PII. Need log scrubbing policy.

4. **Metric Cardinality Explosion** — If we tag metrics by `userId`, and 1M users exist, Prometheus stores 1M time series per metric (memory explosion). Use low-cardinality tags only (tenantId, planType, not userId).

5. **Alertmanager Routing Complexity** — Defining optimal alert routes (critical→pagerduty, warning→slack) requires ongoing tuning. Misconfigurations lead to alert fatigue.

6. **Correlation ID Doesn't Cross Async Boundaries Well** — Event listeners in same process inherit MDC, but if using async message queue (RabbitMQ, Kafka), correlation ID must be explicitly propagated in message headers.

7. **No Cost Tracking** — Metrics don't track infrastructure costs (API calls, database queries). Hard to understand cost impact of individual features.

8. **Log Volume at DEBUG Level** — Setting com.mtbs=DEBUG increases log volume 5-10x, straining disk I/O and log aggregation. Need environment-specific log levels.

---

## Future Improvements

1. **Distributed Tracing Across Services** — Upgrade to microservices (Auth, Billing, Notification as separate JVMs). Sleuth traces automatically propagated via headers. Visualize in Jaeger UI.

2. **Dynamic Log Level Adjustment** — Change log level at runtime (e.g., elevation to DEBUG for specific tenant during incident) without restarting. Implement via Spring Boot Actuator endpoint.

3. **Log Sampling** — Sample high-volume logs (e.g., 1% of GET requests) to reduce storage. Still log all errors and outliers (latency >1s).

4. **Cost Attribution** — Tag metrics with cost_center, feature_flag to track feature profitability. Link infra costs to revenue.

5. **AI-Powered Anomaly Detection** — Detect unusual patterns in error rates, latency, or metric distributions. Alert on deviations, not just thresholds.

6. **Webhook Tracing** — Add X-Correlation-ID to Razorpay webhook events. Trace payment.captured → verification → upgrade → notification all in one flow.

7. **Per-Tenant Metric Isolation** — Separate Prometheus jobs for each tenant (if SaaS with dedicated instances). Avoid cardinality explosion.

8. **Audit Logging** — Separate audit logs for compliance: who changed what plan, when, why. Immutable log destination (e.g., S3) for retention.

---

## Related Documents

- [notification-module.md](./notification-module.md) — Observability in notification event handling
- [payment-processing.md](./payment-processing.md) — Metrics in payment flows
- [scheduler-jobs.md](../06-scheduler/scheduler-jobs.md) — Observability for scheduled tasks
- [ENTERPRISE_REFACTORING_SUMMARY.md](../ENTERPRISE_REFACTORING_SUMMARY.md) — Architectural context
