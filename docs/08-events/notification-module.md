---
version: 1.0
date: 2026-05-16
author: Manoj Pandi
status: Production Ready
tags:
  - notification
  - hexagonal-architecture
  - ports-adapters
  - event-driven
  - email
  - template-rendering
  - outbox-pattern
related_documents:
  - ../01-architecture/outbox-pattern.md
  - ../01-architecture/event-flow.md
  - ../09-jobs/scheduler-jobs.md
  - ../06-api/error-handling.md
---

# Notification Module (Hexagonal Architecture)

## Executive Summary

The **Notification Module** implements **Hexagonal Architecture (Ports & Adapters)** with **Event-Driven Orchestration** and **Transactional Outbox Reliability Pattern** to decouple notification concerns from business logic while guaranteeing **at-least-once delivery semantics**. Core application code depends only on `NotificationPort` abstraction (port); concrete SMTP email implementation is `SmtpEmailProvider` (adapter). Domain events (billing, auth) are consumed by `NotificationEventListener`, which invokes `NotificationOrchestrator` to asynchronously render templates and deliver notifications. All notifications are persisted in the outbox before delivery, surviving application crashes and guaranteeing eventual delivery through intelligent retry logic with exponential backoff. Without this architecture, adding a new channel (SMS, Slack, Push) would require modifying business logic; with it, new adapters can be added independently.

This document explains:
- **Hexagonal layers**: How ports abstract delivery mechanisms
- **Inbound vs outbound ports**: The difference in dependency directions
- **Event-driven flow**: How events decouple producers from consumers
- **At-least-once semantics**: Why you may receive duplicate emails and why that's acceptable
- **Enterprise requirements**: NFR, security, observability, and delivery tracking
- **Production patterns**: Sequence diagrams, security hardening, audit trails

---

## Context / Problem

### Why Hexagonal Architecture for Notifications?

**Tight Coupling Problem:**
```java
// BAD: Direct dependency on SMTP
@Service
public class InvoiceService {
    @Autowired private JavaMailSender mailSender;  // ← Tight coupling
    
    public void createInvoice(Invoice inv) {
        invoice.save();
        mailSender.send(...);  // ← Business logic knows about email
        // If Slack notification added: modify this class
        // If SMS added: modify this class again
    }
}
```

**Hexagonal Solution:**
```java
// GOOD: Depend only on abstraction (port)
@Service
public class InvoiceService {
    @Autowired private NotificationPort notificationPort;  // ← Abstraction
    
    public void createInvoice(Invoice inv) {
        invoice.save();
        publishEvent(new InvoiceCreatedEvent(inv));  // ← Event, not direct call
        // Adapter (SMTP/SMS/Slack) is transparent to this class
    }
}

// Adapter layer (can be swapped)
@Component
public class SmtpEmailProvider implements NotificationPort { ... }
@Component
public class SlackNotificationProvider implements NotificationPort { ... }
@Component
public class TwilioSmsProvider implements NotificationPort { ... }
```

### Why Event-Driven?

Decouples producers from consumers:
- Invoice created → publishes event (does NOT know who consumes it)
- Email listener subscribes → receives event, sends email (does NOT know who published)
- SMS listener subscribes → receives event, sends SMS (independent of email)

If email fails, SMS/Slack still succeed. No cascade failures.

### Why Outbox Pattern?

```
WITHOUT Outbox:
  BEGIN TRANSACTION
  1. INSERT INTO invoices
  2. ApplicationEventPublisher.publish(InvoiceCreatedEvent)  ← Event published
     → NotificationService receives event
     → Sends email (may fail: network timeout)
  3. COMMIT
  
  Problem: Email failure does NOT rollback transaction.
           Invoice created but notification lost if email fails.

WITH Outbox (via OutboxEventProcessor):
  BEGIN TRANSACTION
  1. INSERT INTO invoices
  2. INSERT INTO outbox_events (InvoiceCreatedEvent, PENDING)
  3. COMMIT  ← Transaction complete, event safe in DB

  OutboxEventProcessor polls continuously (every 5s):
  4. SELECT PENDING event FOR UPDATE SKIP LOCKED
  5. Mark as PROCESSING (distributed lock prevents duplicate processing)
  6. ApplicationEventPublisher.publishEvent(InvoiceCreatedEvent)
     → Event delivered to NotificationEventListener
     → Email sent (may fail: network timeout, SMTP down)
  7. If success: UPDATE status=PROCESSED, processedAt=now
  8. If failure: Classify error + schedule retry with exponential backoff

  Result: **AT-LEAST-ONCE DELIVERY** (may send duplicate if crash between step 6-7)
  
  Why at-least-once, not exactly-once?
  - If OutboxEventProcessor crashes AFTER sending email but BEFORE marking PROCESSED,
    the next poll retry-publishes the event → email sent twice
  - This is acceptable because:
    a) Email duplicate is user-friendly (just read it again)
    b) Exactly-once would require 2-phase commit (complex, unscalable)
    c) Idempotency key prevents duplicate charges on payments
```

---

## Dependencies

### Inbound (What triggers notifications)
- **Domain Events** (from Outbox)
  - `BillingEvent` — Invoice/Payment/Subscription events
  - `AuthNotificationEvent` — User/Password/Login events
  - Via `@EventListener` in `NotificationEventListener`
- **Job Scheduler** — `OutboxEventProcessor` polls and publishes events
- **API endpoints** — Events published on domain actions

### Outbound (What notification module calls)
- `NotificationPort` (abstraction) → routed to adapter
- `EmailPort` (SMTP adapter) → `JavaMailSender` → SMTP server
- `TemplateRenderer` → Thymeleaf → render HTML/text templates
- `ApplicationEventPublisher` → receive domain events

### Configuration
- `application.yaml` key: `spring.mail.*` — SMTP settings (host, port, user, password)
- `application.yaml` key: `app.notification.*` — Notification properties (from-name, from-email)
- `application.yaml` key: `app.frontend-url` — Used in email templates for links
- Template files: `src/main/resources/templates/emails/` — Thymeleaf templates

---

## Design / Implementation

### Hexagonal Layers

```
┌─────────────────────────────────────────────────────────────┐
│                     DOMAIN CORE                             │
│  (NotificationRequest, NotificationChannel, Priority)      │
├─────────────────────────────────────────────────────────────┤
│                  APPLICATION LAYER                          │
│ NotificationService, NotificationOrchestrator               │
├─────────────────────────────────────────────────────────────┤
│                    PORTS (Abstractions)                     │
│  NotificationPort (supportedChannel, deliver)              │
│  EmailPort (send)                                           │
│  TemplateRenderer (render)                                  │
├─────────────────────────────────────────────────────────────┤
│                ADAPTERS (Implementations)                   │
│  SmtpEmailProvider implements NotificationPort + EmailPort │
│  ThymeleafTemplateRenderer implements TemplateRenderer     │
├─────────────────────────────────────────────────────────────┤
│              INFRASTRUCTURE (External Services)             │
│  JavaMailSender (Spring Mail), Thymeleaf (Template Engine) │
└─────────────────────────────────────────────────────────────┘
```

### Port Definitions

**NotificationPort (abstraction):**
```java
public interface NotificationPort {
    NotificationChannel supportedChannel();
    void deliver(NotificationRequest request, String renderedContent);
}
```

- `supportedChannel()` — Returns which channel this port handles (EMAIL, SMS, SLACK, etc.)
- `deliver()` — Execute delivery logic (send email, SMS, etc.)

**EmailPort (SMTP-specific port):**
```java
public interface EmailPort {
    void send(EmailMessage message);
}
```

- Wraps JavaMailSender for SMTP-specific operations
- Handles MimeMessage creation, attachment handling, etc.

**TemplateRenderer (abstraction):**
```java
public interface TemplateRenderer {
    String render(String templateName, Map<String, Object> variables);
}
```

- Takes template name (e.g., "auth/welcome") and variables
- Returns rendered HTML/text string

### Inbound vs Outbound Ports

**Inbound Port (Use Case):**
```java
public interface SendNotificationUseCase {
    void send(NotificationRequest request);
}
```
- Called by: `NotificationService` (application layer)
- Used by: `NotificationEventListener`, `NotificationOrchestrator`
- Direction: External actor (event) → Core domain
- Decouples domain from specific notification trigger mechanism

**Outbound Ports (Driven Port / Service Port):**
```java
public interface NotificationPort {
    NotificationChannel supportedChannel();
    void deliver(NotificationRequest request, String renderedContent);
}

public interface EmailPort {
    void send(EmailMessage message);
}

public interface TemplateRenderer {
    String render(String templateName, Map<String, Object> variables);
}
```
- Called by: Core domain (`NotificationService`, `NotificationOrchestrator`)
- Implemented by: Adapters (`SmtpEmailProvider`, `ThymeleafTemplateRenderer`)
- Direction: Core domain → External services (SMTP, rendering engine)
- Decouples domain from specific implementation details (could be SES, SendGrid, SMTP)

**Dependency Flow (Hexagonal):**
```
    External Event
          ↓
   NotificationEventListener (inbound adapter)
          ↓
   NotificationService (application logic)
          ↓
  [NotificationPort interface] ← Core only depends on abstraction
          ↓
  SmtpEmailProvider (outbound adapter)
          ↓
      SMTP Server
```

### Adapter Implementations

**SmtpEmailProvider (implements both ports):**

```java
@Component
public class SmtpEmailProvider implements EmailPort, NotificationPort {
    
    @Override
    public NotificationChannel supportedChannel() {
        return NotificationChannel.EMAIL;  // ← Declares support for EMAIL
    }
    
    @Override
    public void deliver(NotificationRequest request, String renderedContent) {
        EmailMessage message = EmailMessage.builder()
            .to(request.getRecipient())
            .subject(lookupSubject(request.getTemplateName()))
            .body(renderedContent)
            .attachment(request.getPdfAttachment(), request.getPdfFileName())
            .build();
        
        send(message);
    }
    
    @Override
    public void send(EmailMessage message) {
        // Create MimeMessage, attach PDF, send via SMTP
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setTo(message.getTo());
        helper.setSubject(message.getSubject());
        helper.setText(message.getBody(), true);  // true = HTML
        
        if (message.getAttachment() != null) {
            helper.addAttachment(message.getAttachmentName(),
                new ByteArrayResource(message.getAttachment()));
        }
        
        mailSender.send(mime);
    }
}
```

**ThymeleafTemplateRenderer (implements TemplateRenderer):**

```java
@Component
public class ThymeleafTemplateRenderer implements TemplateRenderer {
    
    @Autowired private TemplateEngine templateEngine;
    
    @Override
    public String render(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        return templateEngine.process(templateName, context);
    }
}
```

### Event-Driven Notification Flow

```
1. Business Logic publishes event
   invoiceService.createInvoice(inv)
   → publishEvent(new InvoiceCreatedEvent(...))
   → Event saved to outbox_events table

2. OutboxEventProcessor polls (every 5s)
   → Reads PENDING event
   → ApplicationEventPublisher.publishEvent(InvoiceCreatedEvent)
   → Event propagates to listeners

3. NotificationEventListener receives event
   @EventListener
   public void handleBillingEvent(BillingEvent event) {
       notificationService.handleBillingEvent(event);
   }

4. NotificationService processes event
   → Creates NotificationRequest (recipient, template, variables)
   → Calls orchestrator.process(request)

5. NotificationOrchestrator orchestrates asynchronously
   @Async
   public void process(NotificationRequest request) {
       String rendered = templateRenderer.render(
           request.getTemplateName(),
           request.getVariables());
       channelRouter.route(request, rendered);
   }

6. ChannelRouter routes to correct adapter
   ports.stream()
       .filter(port -> port.supportedChannel() == EMAIL)
       .findFirst()
       .deliver(request, rendered);  // → SmtpEmailProvider.deliver()

7. SmtpEmailProvider sends email
   send(EmailMessage with rendered HTML)
   → JavaMailSender.send(MimeMessage)
   → SMTP server delivers email
```

### Sequence Diagram

```
InvoiceService        Outbox              OutboxEventProcessor   NotificationListener
    │                   │                         │                      │
    ├─ createInvoice()   │                         │                      │
    ├─ INSERT invoice    │                         │                      │
    ├─ publishEvent()    │                         │                      │
    │  InvoiceCreated    │                         │                      │
    ├─ INSERT INTO       │                         │                      │
    │  outbox_events     │                         │                      │
    │  (PENDING)         │                         │                      │
    ├─ COMMIT transaction│                         │                      │
    └─────────────────────────────────────────────────────────────────────┘
                         │                         │                      │
                         │  [Process crash here: event safe in DB]         │
                         │                         │                      │
                         │                         ├─ poll PENDING (5s)   │
                         │                         ├─ SELECT FOR UPDATE  │
                         │                         │  SKIP LOCKED        │
                         │                         ├─ mark PROCESSING    │
                         │                         ├─ publishEvent()     │
                         │                         │  InvoiceCreated     │
                         │                         │                      ├─ @EventListener
                         │                         │                      ├─ handleBillingEvent()
                         │                         │                      ├─ notificationService
                         │                         │                      │  .handleBillingEvent()
                         │                         │                      │
                         │                         │                      NotificationService
                         │                         │                           │
                         │                         │                      ├─ get template config
                         │                         │                      ├─ buildVariables()
                         │                         │                      ├─ create NotificationRequest
                         │                         │                      ├─ orchestrator.process()
                         │                         │                      │
                         │                         │                      NotificationOrchestrator
                         │                         │                           │
                         │                         │                      ├─ @Async
                         │                         │                      ├─ templateRenderer.render()
                         │                         │                      ├─ channelRouter.route()
                         │                         │                      │
                         │                         │                      SmtpEmailProvider
                         │                         │                           │
                         │                         │                      ├─ EmailMessage.build()
                         │                         │                      ├─ send() via JavaMailSender
                         │                         │                      ├─ SMTP delivery
                         │                         │                      │
                         │                         │  mark PROCESSED     │
                         │                         │  save to DB         │
                         └────────────────────────────────────────────────┘
```

### Channel Router (Adapter Pattern)

**ChannelRouter discovers all NotificationPort implementations:**

```java
@Component
public class ChannelRouter {
    
    private final List<NotificationPort> ports;  // ← All port implementations
    
    public void route(NotificationRequest request, String renderedContent) {
        ports.stream()
            .filter(port -> port.supportedChannel() == request.getChannel())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No provider for channel: " + request.getChannel()))
            .deliver(request, renderedContent);
    }
}
```

Spring auto-discovers all `@Component` implementations of `NotificationPort`:
- `SmtpEmailProvider` (current)
- `SlackNotificationProvider` (when added)
- `TwilioSmsProvider` (when added)

Router selects adapter dynamically based on channel.

### Template Configuration

**EmailTemplateConfig (central registry):**

```java
private static final Map<NotificationEvent, TemplateDefinition> TEMPLATES =
    Map.ofEntries(
        Map.entry(NotificationEvent.INVOICE_GENERATED,
            new TemplateDefinition(
                "billing/invoice-generated",  // Template file path
                "Invoice {{invoiceNumber}} is ready"  // Subject (with variables)
            )),
        Map.entry(NotificationEvent.PAYMENT_FAILED,
            new TemplateDefinition(
                "billing/payment-failed",
                "Payment failed — Action required"
            ))
        // ... 20+ more templates
    );
```

Maps `NotificationEvent` to template metadata (file path, subject line).

### Template Variables

**NotificationService builds variable maps:**

```java
private Map<String, Object> buildBillingVariables(BillingEvent event) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("frontendUrl", frontendUrl);
    variables.put("currentYear", Year.now().getValue());
    variables.put("companyName", properties.getFromName());
    variables.put("invoiceNumber", event.getInvoiceNumber());
    variables.put("amount", formatAmount(event.getAmount()));
    variables.put("dueDate", formatDate(event.getDueDate()));
    variables.put("recipientName", event.getRecipientName());
    // ... more variables
    return variables;
}
```

Variables passed to Thymeleaf template engine for rendering.

---

## Flow Diagram

```
┌───────────────────────────────────────────────────────────────────┐
│                    DOMAIN LAYER                                   │
│  InvoiceService.createInvoice() → publishEvent(InvoiceCreated)   │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│          OUTBOX (Transactional Reliability)                       │
│  INSERT INTO outbox_events(InvoiceCreatedEvent, PENDING)         │
│  COMMIT  ← Event survives process crash                          │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│        OutboxEventProcessor (Scheduler Job - every 5s)            │
│  1. SELECT PENDING events FOR UPDATE SKIP LOCKED                 │
│  2. ApplicationEventPublisher.publishEvent(InvoiceCreatedEvent)  │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│      NotificationEventListener (@EventListener)                   │
│  Receives InvoiceCreatedEvent                                    │
│  Calls notificationService.handleBillingEvent()                  │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│         NotificationService (Application Logic)                   │
│  1. Get template config for event                                │
│  2. Build variables map (invoice#, amount, date, etc.)           │
│  3. Create NotificationRequest                                    │
│  4. Call orchestrator.process(request)                            │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│      NotificationOrchestrator (@Async)                            │
│  1. TemplateRenderer.render(templateName, variables)             │
│     → Thymeleaf processes HTML template                          │
│  2. ChannelRouter.route(request, rendered)                        │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│         ChannelRouter (Port Discovery)                            │
│  1. Find NotificationPort for channel=EMAIL                      │
│  2. Call port.deliver(request, rendered)                          │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│    SmtpEmailProvider (Adapter - implements NotificationPort)      │
│  1. Create EmailMessage (to, subject, body, attachments)         │
│  2. Call send(EmailMessage)                                       │
│     → MimeMessageHelper builds MimeMessage                        │
│     → JavaMailSender.send(mime)                                   │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌───────────────────────────────────────────────────────────────────┐
│           EXTERNAL SMTP SERVER                                     │
│  SMTP connection → SMTP AUTH → Message delivery → User inbox     │
└───────────────────────────────────────────────────────────────────┘
```

---

## Code References

| Class | Tag | Method/Interface | Purpose |
|-------|-----|------------------|---------|
| `NotificationPort` | [NOTIF-1] | `supportedChannel()`, `deliver()` | Port: notification delivery contract |
| `EmailPort` | [NOTIF-2] | `send()` | Port: SMTP-specific operations |
| `TemplateRenderer` | [NOTIF-3] | `render()` | Port: template rendering |
| `SmtpEmailProvider` | [NOTIF-4] | `deliver()`, `send()` | Adapter: email delivery implementation |
| `ThymeleafTemplateRenderer` | [NOTIF-5] | `render()` | Adapter: template rendering implementation |
| `NotificationService` | [NOTIF-6] | `handleBillingEvent()`, `handleAuthEvent()` | Application: event processing |
| `NotificationOrchestrator` | [NOTIF-7] | `process()` | Application: orchestration of template + routing |
| `ChannelRouter` | [NOTIF-8] | `route()` | Adapter discovery and routing |
| `NotificationEventListener` | [NOTIF-9] | `handleBillingEvent()`, `handleAuthEvent()` | Event consumer |
| `EmailTemplateConfig` | [NOTIF-10] | `getTemplate()` | Template registry (event → template mapping) |
| `NotificationRequest` | [NOTIF-11] | (domain object) | Notification data transfer object |

---

## Non-Functional Requirements (NFR)

| Requirement | Target | Justification |
|---|---|---|
| **Delivery Latency** | < 30 seconds (p95) | User experiences timely notifications (password reset, payment confirmation) |
| **Availability** | 99.9% (excluding outbox processor downtime) | Transactional outbox decouples from email provider failures |
| **Delivery Success Rate** | > 99% (with retries) | Exponential backoff retry ensures eventual delivery except permanent failures |
| **No Duplicate Delivery** | At-least-once semantics (may send twice) | Idempotency key prevents duplicate charges; email dup is user-friendly vs missing |
| **Horizontal Scalability** | Linear with thread pool size | `@Async` orchestrator scales independently; no shared state |
| **Multi-tenant Isolation** | 100% (no cross-tenant leak) | TenantContext ensures tenant-scoped events; schema isolation via tenant ID |
| **Email Auditability** | All notifications logged | Future `notification_delivery_log` table enables compliance + debugging |
| **Error Observability** | 100% of errors logged | Structured logs with correlationId, tenantId, eventType enable tracing |
| **Concurrent Throughput** | 1000+ notifications/minute | Async thread pool (default 10 core + 100 queue) + batch outbox polling |
| **Database Impact** | Minimal write amplification | Outbox table append-only; events processed once then deleted after 30 days |

---

## Security Considerations

### SMTP Credentials
- **Threat**: Plaintext credentials in config files
- **Mitigation**: Use Spring Cloud Config or AWS Secrets Manager
  ```yaml
  spring:
    mail:
      host: ${MAIL_HOST}  # from environment/vault
      username: ${MAIL_USERNAME}
      password: ${MAIL_PASSWORD}
  ```
- **Audit**: Log SMTP connection success (without credentials)

### Template Injection & XSS
- **Threat**: Attacker injects template syntax via event data (e.g., `{{T(java.lang.Runtime).getRuntime().exec('cmd')}}` in Thymeleaf)
- **Mitigation**: Thymeleaf escapes HTML by default; validate event input before storage
- **Rule**: Never trust event.recipientEmail, event.recipientName, custom fields

### Sensitive Data in Templates
- **Threat**: Full credit card, password, API key in email
- **Mitigation**: Never include sensitive data in email body
  ```java
  // BAD
  variables.put("apiKey", user.getApiKey());  // ← Exposed in email
  
  // GOOD
  variables.put("resetLink", "https://app.com/reset?token=xyz");  // ← Token is temporary + scoped
  ```
- **Pattern**: Use short-lived tokens/links, not credentials

### Tenant Isolation in Notifications
- **Threat**: Email sent to wrong tenant (cross-tenant data leak)
- **Mitigation**:
  1. Event is already tenant-scoped (BillingEvent.tenantId)
  2. NotificationService.buildBillingVariables() uses tenant-scoped data only
  3. Email recipient verified from tenant's user record
  ```java
  // Each event tied to single tenant
  BillingEvent event = BillingEvent.builder()
      .tenantId(getTenantId())  // ← Set by invoker
      .recipientEmail(invoice.getOwner().getEmail())  // ← Resolved within tenant schema
      .build();
  ```

### Rate Limiting for OTP/Reset Emails
- **Threat**: Attacker triggers 1000 password reset emails to spam user inbox
- **Mitigation**: Implement per-email rate limit (max 3 resets per 15 minutes)
  ```java
  @PreAuthorize("@rateLimiter.allowForgotPassword(#email)")
  public void forgotPassword(String email) { ... }
  ```
- **Monitoring**: Alert if rate limit triggered frequently

### Attachment Validation
- **Threat**: PDF attachment with malware
- **Mitigation**:
  1. Only generated PDFs (invoices) attached; not user-uploaded files
  2. Size limit: < 10MB (typical email limit: 25-50MB)
  3. MIME type validation: application/pdf only
  ```java
  if (request.getPdfAttachment() != null && request.getPdfAttachment().length > 10 * 1024 * 1024) {
      throw new ValidationException("PDF too large");
  }
  ```

### Audit Logging for Critical Emails
- **Critical emails**: Password reset, subscription cancellation, payment failure
- **Log**: recipient, subject, template, timestamp, status
  ```java
  if (event.getEventType() == NotificationEvent.PASSWORD_RESET_REQUESTED) {
      auditLog.log("PASSWORD_RESET_EMAIL_SENT", event.getTenantId(), event.getRecipientEmail());
  }
  ```

---

## Observability

### Structured Logging

**OutboxEventProcessor logs with correlation ID:**
```
2026-05-16 14:23:45.123 INFO [OUTBOX] [correlationId=a1b2c3d4] eventType=INVOICE_GENERATED aggregateId=INV-12345 retryCount=0
2026-05-16 14:23:45.456 INFO [OUTBOX] [correlationId=a1b2c3d4] SUCCESS eventType=INVOICE_GENERATED durationMs=333
```

**NotificationOrchestrator logs async failures:**
```
2026-05-16 14:23:50.123 ERROR [correlationId=a1b2c3d4] [tenantId=100] Notification failed: template=billing/invoice-generated recipient=user@example.com error=MessagingException: Connection refused
```

### Key Fields to Log
- `correlationId` — Trace across outbox → listener → service → adapter
- `tenantId` — Multi-tenant debugging (which customer affected?)
- `eventType` — What notification (INVOICE_GENERATED, PASSWORD_RESET, etc.)
- `recipient` — Who received it (helps find bounces/complaints)
- `templateName` — Which template (helps debug rendering)
- `durationMs` — Performance monitoring
- `errorType` — Classification (TRANSIENT, PERMANENT, VALIDATION)

### Metrics (Micrometer)
```java
meterRegistry.counter("notification.send.count", "channel", "EMAIL", "event", "INVOICE_GENERATED").increment();
meterRegistry.timer("notification.send.duration", "channel", "EMAIL").record(durationMs, TimeUnit.MILLISECONDS);
meterRegistry.counter("notification.send.failures", "errorType", "TRANSIENT").increment();
```

### Alerting
- **Alert**: `notification.send.failures > 100/minute` (email service down)
- **Alert**: `notification.retry.count > 1000` (backlog building)
- **Alert**: `outbox.processing_duration > 60s` (processor stalled)

### Dead Letter Monitoring
```sql
SELECT COUNT(*) FROM outbox_events WHERE status = 'FAILED' AND retry_count >= 5;
```
- **Runbook**: Manual review of FAILED events; update template or add error handling

---

## notification_delivery_log Database Schema

**Purpose**: Track delivery status for debugging, compliance (GDPR), and reporting.

```sql
CREATE TABLE notification_delivery_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    notification_event VARCHAR(100) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    template_name VARCHAR(255),
    subject_line TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING → SENT → DELIVERED
    -- or PENDING → FAILED
    retry_count INT DEFAULT 0,
    failure_reason TEXT,
    correlation_id VARCHAR(50),
    aggregate_type VARCHAR(100),
    aggregate_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP,
    delivered_at TIMESTAMP,
    bounced_at TIMESTAMP,
    complained_at TIMESTAMP,
    
    CONSTRAINT fk_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    INDEX idx_tenant_created (tenant_id, created_at DESC),
    INDEX idx_recipient_status (recipient_email, status),
    INDEX idx_correlation (correlation_id)
);
```

**Key Columns:**
- `status` — PENDING (queued), SENT (accepted by SMTP), DELIVERED (user received), FAILED, BOUNCED, COMPLAINED
- `retry_count` — How many times OutboxEventProcessor retried
- `failure_reason` — Error message if FAILED (e.g., "MessagingException: 550 user not found")
- `correlation_id` — Links to OutboxEvent for tracing
- `bounced_at`, `complained_at` — Set when webhook received (for GDPR unsubscribe)

**Usage Examples:**
```sql
-- Find all failed emails for tenant 100
SELECT * FROM notification_delivery_log
WHERE tenant_id = 100 AND status = 'FAILED'
ORDER BY created_at DESC LIMIT 10;

-- Report: delivery success rate
SELECT
    notification_event,
    COUNT(*) as total,
    COUNT(CASE WHEN status IN ('SENT', 'DELIVERED') THEN 1 END) as successful,
    ROUND(100.0 * COUNT(CASE WHEN status IN ('SENT', 'DELIVERED') THEN 1 END) / COUNT(*), 2) as success_rate
FROM notification_delivery_log
WHERE created_at >= NOW() - INTERVAL '24 hours'
GROUP BY notification_event;

-- Find recipients with bounce/complaint
SELECT DISTINCT recipient_email
FROM notification_delivery_log
WHERE bounced_at IS NOT NULL OR complained_at IS NOT NULL;
```

**Integration Points:**
1. **NotificationService** — After sending, insert record with status=SENT
2. **SMTP Webhook Handler** — Receive bounce/complaint webhooks, update status + bounced_at/complained_at
3. **Dashboard** — Query for delivery metrics, failure analysis

---

## Rules / Constraints

1. **All Port Dependencies** — Application layer (`NotificationService`) depends only on `NotificationPort`, never on concrete adapters. Enables swapping adapters without code changes.

2. **Async Orchestration** — `NotificationOrchestrator.process()` is `@Async` (runs in thread pool). Prevents blocking on email send. Failures logged but don't block caller.

3. **Template Variables Must Be Immutable** — Variables map passed to Thymeleaf must not be modified during rendering. Pass copy if modification needed.

4. **Event-Driven Only** — Notifications triggered by events, never direct API calls. Ensures all notifications logged in outbox (reliability guarantee).

5. **Idempotent Delivery** — Rendering same template with same variables produces identical email. Multiple deliveries of same notification are harmless (user gets duplicate email, but data not corrupted).

6. **Error Handling Graceful** — Template rendering error or email send failure is logged but does NOT crash caller. Outbox retry logic handles eventual delivery.

7. **PDF Attachments Supported** — Binary attachments (invoices as PDF) passed via `pdfAttachment` field. Handled by MimeMessageHelper.addAttachment().

8. **Subject Line Variable Interpolation** — Subject templates support variable interpolation (e.g., "Invoice {{invoiceNumber}} is ready" → "Invoice INV-123 is ready"). Custom resolution in `NotificationService`.

---

## Failure Scenarios

| Scenario | Error Class | Recovery |
|----------|---|---|
| SMTP server unreachable | `MessagingException` | Logged in orchestrator catch block; Outbox retry after exponential backoff |
| Invalid template name | `IllegalArgumentException` | NotificationService catches; logged as warning |
| Thymeleaf render error | `TemplateProcessingException` | Orchestrator catches; event marked FAILED in outbox, retried |
| Recipient email invalid | `AddressException` | JavaMailSender throws on send(); caught by orchestrator; logged |
| Attachment too large | `MessageLengthExceededException` | JavaMailSender throws; caught; logged as error |
| No NotificationPort for channel | `IllegalStateException` | ChannelRouter throws; orchestrator catches; logged |
| OutboxEventProcessor crashes mid-publish | (None — process crash) | Event remains PENDING; next poll re-publishes (at-least-once delivery guarantee via outbox—may send duplicate) |

---

## Edge Cases

- **Concurrency: Multiple events for same tenant** — `@Async` thread pool serializes by default (single thread per service). Multiple async tasks may execute concurrently if thread pool size > 1. No data corruption; each task is independent.

- **Concurrency: Template rendering with shared state** — Thymeleaf engine is thread-safe. Multiple threads can call `render()` simultaneously. No shared state.

- **Timezone: Email date/time variables** — Variables formatted in UTC (via `DateTimeFormatter.withZone(UTC)`). Recipient sees UTC times. Consider tenant locale in future.

- **Empty state: No variables** — Template rendered with empty variables map. Undefined template variables render as empty string (Thymeleaf default).

- **Email size: Attachment too large** — SMTP has size limits (typically 25-50MB). Large PDFs (invoices) may exceed limit. Catch `MessageLengthExceededException` and provide user-friendly error.

- **HTML rendering: XSS in variables** — Thymeleaf auto-escapes HTML by default (safe). If custom variables contain `<script>`, rendered as `&lt;script&gt;` (harmless in email).

- **Channel not implemented** — If caller requests SMS channel but no adapter registered, ChannelRouter throws `IllegalStateException`. Gracefully handled by orchestrator.

- **Tenant isolation: Email to wrong tenant** — `NotificationService.buildBillingVariables()` uses event data (tenant-scoped). No cross-tenant leak possible (event scoped to tenant).

---

## Known Issues / Limitations

1. **No SMS Channel** — `NotificationChannel` enum has EMAIL only. SMS/Push are not implemented yet. Adding requires:
   - Implement `TwilioSmsProvider implements NotificationPort`
   - Add `SMS` to enum
   - Add SMS templates to `EmailTemplateConfig`

2. **Email Template Hardcoded** — Subject lines and template paths hardcoded in `EmailTemplateConfig`. Should be externalized to database for operator control.

3. **No Delivery Status Tracking** — No record of which emails were successfully sent, failed, or bounced. Suggestion: Add `notification_delivery_log` table.

4. **No Unsubscribe Support** — Emails lack unsubscribe link (List-Unsubscribe header). GDPR-required for transactional emails. Need to add unsubscribe list + header.

5. **Template Caching** — Thymeleaf caches compiled templates. Changes to template files require app restart. Use development mode to disable caching.

6. **No Retry Backoff Tuning** — Email send failures retry via Outbox with fixed exponential backoff (1m → 5m → 25m). No tuning per channel type (email might tolerate longer backoff than SMS).

7. **Async Executor Not Tuned** — Spring `@Async` uses default executor (typically 10 core + 100 queue). High volume of emails may queue. No monitoring of queue depth.

---

## Future Improvements

1. **SMS Channel via Twilio** — Implement `TwilioSmsProvider implements NotificationPort`. Routes SMS requests to Twilio API. Requires integration testing with Twilio.

2. **Delivery Status Tracking** — Add `notification_delivery_log` table. Track sent/failed/bounced with timestamps. Enable reporting: "Which tenants had failed email sends?"

3. **Template Database** — Move template paths/subjects to database table. Operator can edit templates without code deployment.

4. **Unsubscribe Support** — Add List-Unsubscribe header. Maintain `email_unsubscribe` list. Skip notifications for unsubscribed users.

5. **Async Executor Tuning** — Expose `ThreadPoolTaskExecutor` properties via `application.yaml`. Allow configuring core pool size, max pool size, queue capacity.

6. **Delivery Metrics** — Micrometer metrics: `notification.send.count`, `notification.send.duration`, `notification.send.failures`. Enable monitoring dashboards.

7. **Selective Retry** — Different retry policies for different error types. Transient errors (timeout) retry sooner; permanent errors (invalid address) fail immediately.

8. **Webhook Integration** — Listen to SMTP provider webhooks (bounce, complaint) to update email lists automatically. Implement `WebhookHandler` for SES/SendGrid bounce notifications.

---

## Related Documents

- [outbox-pattern.md](../01-architecture/outbox-pattern.md) — Outbox reliability (ensures notification delivery)
- [event-flow.md](../01-architecture/event-flow.md) — Domain events that trigger notifications
- [scheduler-jobs.md](../09-jobs/scheduler-jobs.md) — Job scheduling (OutboxEventProcessor publishes events)
- [error-handling.md](../06-api/error-handling.md) — Exception handling patterns
