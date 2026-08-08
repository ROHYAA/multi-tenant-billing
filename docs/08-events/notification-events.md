---
version: 1.0
date: 2026-05-17
author: Manoj Pandi
status: Production Ready
tags:
  - notification-events
  - event-types
  - email-templates
  - event-handlers
  - user-notifications
  - transactional-emails
related_documents:
  - ./domain-events.md
  - ./notification-module.md
  - ./observability.md
---

# Notification Events: Types, Handlers, & Email Templates

## Executive Summary

**Notification Events** are specific domain events that trigger user communications (emails, SMS). MTBS defines 15+ notification event types covering user lifecycle (registration, payment, cancellation, etc.).

This document catalogs:
1. **Event Type Definitions** — all notification events with payloads
2. **Email Templates** — Thymeleaf templates for each event
3. **Event Handlers** — how NotificationService reacts to events
4. **Retry Logic** — resending failed emails
5. **Unsubscribe & Preferences** — user control over notifications

Key insight: Not all domain events require notifications. Only user-facing, critical business events.

---

## Context / Problem

### Historical Problem: Ad-Hoc Email Sending

**Before event-driven notifications:**
```
Feature: Send upgrade confirmation email

Code scattered across multiple services:
  PaymentService.capturePayment() {
    ... payment logic ...
    emailService.sendUpgradeConfirmation(tenantId, planName);  // Hardcoded here
  }
  
  SubscriptionService.upgradePlan() {
    ... upgrade logic ...
    emailService.sendUpgradeConfirmation(tenantId, planName);  // Duplicated here too
  }
  
Problems:
1. Email logic coupled to business logic (hard to test)
2. Email sending delays business transaction (slower API response)
3. If email fails, business transaction also fails (unnecessarily blocks user)
4. Different email content in different code paths (inconsistent)
5. Hard to add "send SMS too" — affects multiple places
```

**With event-driven notifications:**
```
1. PaymentService publishes: new PaymentCapturedEvent(...)
2. SubscriptionService publishes: new PlanUpgradedEvent(...)
3. NotificationEventHandler listens to both events
4. Sends single, consistent email template

Benefits:
- Business logic decoupled from email logic
- Email sending async (doesn't block API response)
- Email failures don't affect business transaction
- Single source of truth for email content
- Easy to add SMS, Slack notifications later
```

---

## Dependencies

### Inbound (Who triggers notification events)
- **PaymentService** → PaymentCapturedEvent, PaymentFailedEvent
- **SubscriptionService** → SubscriptionCreatedEvent, PlanUpgradedEvent, DowngradedEvent, CancelledEvent
- **InvoiceService** → InvoiceGeneratedEvent, InvoiceOverdueEvent, InvoicePaidEvent
- **UserService** → UserRegisteredEvent, PasswordResetRequestedEvent

### Outbound (Who consumes notification events)
- **NotificationEventHandler** — listens to all domain events
- **EmailService** — sends emails via SMTP
- **NotificationDeliveryLog** — tracks email delivery status
- **AnalyticsService** — measures email performance

### Configuration
```yaml
app:
  notifications:
    enabled: true
    
    templates:
      engine: THYMELEAF
      path: classpath:templates/emails
    
    defaults:
      from: noreply@example.com
      from-name: MTBS Billing
      reply-to: support@example.com
    
    retry:
      enabled: true
      max-attempts: 3
      backoff-seconds: 60
    
    events:
      send-on:
        - USER_REGISTERED
        - SUBSCRIPTION_CREATED
        - PAYMENT_CAPTURED
        - PLAN_UPGRADED
        - INVOICE_GENERATED
        - INVOICE_PAID
        - CANCELLATION_CONFIRMED
```

---

## Event Type Definitions

### Category 1: User Lifecycle Events

#### USER_REGISTERED

```java
@Data
public class UserRegisteredEvent extends DomainEvent {
    private String userId;
    private String userEmail;
    private String firstName;
    private String lastName;
    private Instant registeredAt;
    private String activationToken;  // For email verification
    
    public UserRegisteredEvent(
        String userId, String userEmail, String firstName, String lastName,
        String activationToken, String tenantId, String correlationId) {
        
        super(userId, "User", tenantId, correlationId);
        this.userId = userId;
        this.userEmail = userEmail;
        this.firstName = firstName;
        this.lastName = lastName;
        this.activationToken = activationToken;
        this.registeredAt = Instant.now();
    }
}
```

**Email Template:** `user-registered.html`
```html
<h2>Welcome to MTBS!</h2>
<p>Hi [[${firstName}]],</p>
<p>Thank you for signing up. Click below to verify your email:</p>
<a href="https://app.example.com/verify?token=[[${activationToken}]]">
  Verify Email
</a>
<p>This link expires in 24 hours.</p>
```

**Handler:**
```java
@EventListener
@Async
public void on(UserRegisteredEvent event) {
    NotificationContext context = new NotificationContext();
    context.setRecipientEmail(event.getUserEmail());
    context.setTemplateName("user-registered");
    context.addVariable("firstName", event.getFirstName());
    context.addVariable("activationToken", event.getActivationToken());
    context.addVariable("expiresInHours", 24);
    
    notificationService.sendEmail(context);
}
```

#### PASSWORD_RESET_REQUESTED

```java
@Data
public class PasswordResetRequestedEvent extends DomainEvent {
    private String userId;
    private String userEmail;
    private String resetToken;
    private Instant expiresAt;  // Token valid for 1 hour
    
    public PasswordResetRequestedEvent(
        String userId, String userEmail, String resetToken,
        String tenantId, String correlationId) {
        
        super(userId, "User", tenantId, correlationId);
        this.userId = userId;
        this.userEmail = userEmail;
        this.resetToken = resetToken;
        this.expiresAt = Instant.now().plusSeconds(3600);  // 1 hour
    }
}
```

**Email Template:** `password-reset.html`
```html
<h2>Reset Your Password</h2>
<p>Click the link below to reset your password:</p>
<a href="https://app.example.com/reset-password?token=[[${resetToken}]]">
  Reset Password
</a>
<p>This link expires in 1 hour.</p>
<p>If you didn't request this, ignore this email.</p>
```

---

### Category 2: Subscription Lifecycle Events

#### SUBSCRIPTION_CREATED

```java
@Data
public class SubscriptionCreatedEvent extends DomainEvent {
    private String subscriptionId;
    private String planId;
    private String planName;
    private BigDecimal planPrice;
    private BillingCycle billingCycle;
    private Integer trialDays;
    private Instant trialEndsAt;
    
    public SubscriptionCreatedEvent(
        String subscriptionId, String planId, String planName,
        BigDecimal planPrice, BillingCycle cycle, Integer trialDays,
        Instant trialEnd, String tenantId, String userId, String correlationId) {
        
        super(subscriptionId, "Subscription", tenantId, correlationId);
        this.subscriptionId = subscriptionId;
        this.planId = planId;
        this.planName = planName;
        this.planPrice = planPrice;
        this.billingCycle = cycle;
        this.trialDays = trialDays;
        this.trialEndsAt = trialEnd;
        this.userId = userId;
    }
}
```

**Email Template:** `subscription-created.html`
```html
<h2>Welcome to [[${planName}]]!</h2>
<p>Your subscription is active.</p>
<table border="1">
  <tr>
    <td>Plan</td>
    <td>[[${planName}]]</td>
  </tr>
  <tr>
    <td>Price</td>
    <td>₹[[${#numbers.formatDecimal(planPrice, 0, 'INDIAN', 2)}]] / [[${billingCycle}]]</td>
  </tr>
  <tr>
    <td>Trial Period</td>
    <td>[[${trialDays}]] days (expires [[${#dates.format(trialEndsAt, 'MMM dd, yyyy')}]])</td>
  </tr>
</table>
<p><a href="https://app.example.com/billing">View Billing Details</a></p>
```

#### PLAN_UPGRADED

```java
@Data
public class PlanUpgradedEvent extends DomainEvent {
    private String subscriptionId;
    private String fromPlanId;
    private String fromPlanName;
    private String toPlanId;
    private String toPlanName;
    private BigDecimal chargeAmount;
    private String invoiceId;
    
    public PlanUpgradedEvent(
        String subscriptionId, String fromPlanId, String fromPlanName,
        String toPlanId, String toPlanName, BigDecimal charge,
        String invoiceId, String tenantId, String userId, String correlationId) {
        
        super(subscriptionId, "Subscription", tenantId, correlationId);
        this.subscriptionId = subscriptionId;
        this.fromPlanId = fromPlanId;
        this.fromPlanName = fromPlanName;
        this.toPlanId = toPlanId;
        this.toPlanName = toPlanName;
        this.chargeAmount = charge;
        this.invoiceId = invoiceId;
        this.userId = userId;
    }
}
```

**Email Template:** `plan-upgraded.html`
```html
<h2>Upgrade Confirmed!</h2>
<p>Your subscription has been upgraded to [[${toPlanName}]].</p>
<table border="1">
  <tr>
    <td>Previous Plan</td>
    <td>[[${fromPlanName}]]</td>
  </tr>
  <tr>
    <td>New Plan</td>
    <td>[[${toPlanName}]]</td>
  </tr>
  <tr>
    <td>Charge Amount</td>
    <td>₹[[${#numbers.formatDecimal(chargeAmount, 0, 'INDIAN', 2)}]]</td>
  </tr>
</table>
<p><a href="https://app.example.com/invoices/[[${invoiceId}]]">View Invoice</a></p>
```

#### PLAN_DOWNGRADED

```java
@Data
public class PlanDowngradedEvent extends DomainEvent {
    private String subscriptionId;
    private String fromPlanName;
    private String toPlanName;
    private Boolean atPeriodEnd;
    private Instant effectiveDate;  // When downgrade takes effect
    
    public PlanDowngradedEvent(
        String subscriptionId, String fromPlanName, String toPlanName,
        Boolean atPeriodEnd, Instant effectiveDate,
        String tenantId, String userId, String correlationId) {
        
        super(subscriptionId, "Subscription", tenantId, correlationId);
        this.subscriptionId = subscriptionId;
        this.fromPlanName = fromPlanName;
        this.toPlanName = toPlanName;
        this.atPeriodEnd = atPeriodEnd;
        this.effectiveDate = effectiveDate;
        this.userId = userId;
    }
}
```

**Email Template:** `plan-downgraded.html`
```html
<h2>Plan Downgrade Confirmed</h2>
<p th:if="${atPeriodEnd}">
  Your subscription will be downgraded to [[${toPlanName}]] at the end of your current billing period.
</p>
<p th:if="not ${atPeriodEnd}">
  Your subscription has been downgraded to [[${toPlanName}]] effective immediately.
</p>
<p>Effective Date: [[${#dates.format(effectiveDate, 'MMM dd, yyyy')}]]</p>
<p><a href="https://app.example.com/billing">Manage Subscription</a></p>
```

#### SUBSCRIPTION_CANCELLED

```java
@Data
public class SubscriptionCancelledEvent extends DomainEvent {
    private String subscriptionId;
    private String planName;
    private String cancellationReason;
    private Instant cancelledAt;
    private Instant lastAccessDate;  // Last day of service
    
    public SubscriptionCancelledEvent(
        String subscriptionId, String planName, String reason,
        Instant lastAccessDate, String tenantId, String userId, String correlationId) {
        
        super(subscriptionId, "Subscription", tenantId, correlationId);
        this.subscriptionId = subscriptionId;
        this.planName = planName;
        this.cancellationReason = reason;
        this.lastAccessDate = lastAccessDate;
        this.cancelledAt = Instant.now();
        this.userId = userId;
    }
}
```

**Email Template:** `subscription-cancelled.html`
```html
<h2>Subscription Cancelled</h2>
<p>Your [[${planName}]] subscription has been cancelled.</p>
<p>Reason: [[${cancellationReason}]]</p>
<p>Last Access Date: [[${#dates.format(lastAccessDate, 'MMM dd, yyyy')}]]</p>
<p>We'd love to have you back anytime. <a href="https://app.example.com/reactivate">Reactivate Subscription</a></p>
```

---

### Category 3: Payment & Invoice Events

#### PAYMENT_CAPTURED

```java
@Data
public class PaymentCapturedEvent extends DomainEvent {
    private String invoiceId;
    private String subscriptionId;
    private BigDecimal amount;
    private String razorpayPaymentId;
    private Instant capturedAt;
    
    public PaymentCapturedEvent(
        String invoiceId, String subscriptionId, BigDecimal amount,
        String razorpayPaymentId, String tenantId, String userId, String correlationId) {
        
        super(invoiceId, "Payment", tenantId, correlationId);
        this.invoiceId = invoiceId;
        this.subscriptionId = subscriptionId;
        this.amount = amount;
        this.razorpayPaymentId = razorpayPaymentId;
        this.capturedAt = Instant.now();
        this.userId = userId;
    }
}
```

**Email Template:** `payment-captured.html`
```html
<h2>Payment Received</h2>
<p>Thank you! We've received your payment.</p>
<table border="1">
  <tr>
    <td>Invoice ID</td>
    <td>[[${invoiceId}]]</td>
  </tr>
  <tr>
    <td>Amount</td>
    <td>₹[[${#numbers.formatDecimal(amount, 0, 'INDIAN', 2)}]]</td>
  </tr>
  <tr>
    <td>Payment ID</td>
    <td>[[${razorpayPaymentId}]]</td>
  </tr>
  <tr>
    <td>Date</td>
    <td>[[${#dates.format(capturedAt, 'MMM dd, yyyy HH:mm:ss')}]]</td>
  </tr>
</table>
<p><a href="https://app.example.com/invoices/[[${invoiceId}]]">View Invoice</a></p>
```

#### PAYMENT_FAILED

```java
@Data
public class PaymentFailedEvent extends DomainEvent {
    private String invoiceId;
    private BigDecimal amount;
    private String failureReason;
    private String supportUrl;  // Link to contact support
    
    public PaymentFailedEvent(
        String invoiceId, BigDecimal amount, String failureReason,
        String tenantId, String userId, String correlationId) {
        
        super(invoiceId, "Payment", tenantId, correlationId);
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.failureReason = failureReason;
        this.supportUrl = "https://support.example.com/contact";
        this.userId = userId;
    }
}
```

**Email Template:** `payment-failed.html`
```html
<h2>Payment Failed</h2>
<p>Your payment of ₹[[${#numbers.formatDecimal(amount, 0, 'INDIAN', 2)}]] could not be processed.</p>
<p>Reason: [[${failureReason}]]</p>
<p>Please <a href="https://app.example.com/billing/retry">try again</a> or <a href="[[${supportUrl}]]">contact support</a>.</p>
```

#### INVOICE_GENERATED

```java
@Data
public class InvoiceGeneratedEvent extends DomainEvent {
    private String invoiceId;
    private String subscriptionId;
    private BigDecimal amount;
    private Instant dueDate;
    private String invoiceUrl;  // Direct link to invoice PDF
    
    public InvoiceGeneratedEvent(
        String invoiceId, String subscriptionId, BigDecimal amount,
        Instant dueDate, String tenantId, String userId, String correlationId) {
        
        super(invoiceId, "Invoice", tenantId, correlationId);
        this.invoiceId = invoiceId;
        this.subscriptionId = subscriptionId;
        this.amount = amount;
        this.dueDate = dueDate;
        this.invoiceUrl = "https://app.example.com/invoices/" + invoiceId + "/pdf";
        this.userId = userId;
    }
}
```

**Email Template:** `invoice-generated.html`
```html
<h2>Invoice Available</h2>
<p>Your invoice [[${invoiceId}]] is ready.</p>
<table border="1">
  <tr>
    <td>Amount Due</td>
    <td>₹[[${#numbers.formatDecimal(amount, 0, 'INDIAN', 2)}]]</td>
  </tr>
  <tr>
    <td>Due Date</td>
    <td>[[${#dates.format(dueDate, 'MMM dd, yyyy')}]]</td>
  </tr>
</table>
<p><a href="[[${invoiceUrl}]]">Download Invoice (PDF)</a></p>
```

#### INVOICE_OVERDUE

```java
@Data
public class InvoiceOverdueEvent extends DomainEvent {
    private String invoiceId;
    private BigDecimal amountDue;
    private Instant originalDueDate;
    private Integer daysOverdue;
    
    public InvoiceOverdueEvent(
        String invoiceId, BigDecimal amountDue, Instant originalDueDate,
        Integer daysOverdue, String tenantId, String userId, String correlationId) {
        
        super(invoiceId, "Invoice", tenantId, correlationId);
        this.invoiceId = invoiceId;
        this.amountDue = amountDue;
        this.originalDueDate = originalDueDate;
        this.daysOverdue = daysOverdue;
        this.userId = userId;
    }
}
```

**Email Template:** `invoice-overdue.html`
```html
<h2>Payment Overdue</h2>
<p>Invoice [[${invoiceId}]] is now [[${daysOverdue}]] days overdue.</p>
<p>Amount Due: ₹[[${#numbers.formatDecimal(amountDue, 0, 'INDIAN', 2)}]]</p>
<p>Original Due Date: [[${#dates.format(originalDueDate, 'MMM dd, yyyy')}]]</p>
<p><a href="https://app.example.com/invoices/[[${invoiceId}]]/pay">Pay Now</a></p>
```

---

## Event Handler Implementation

```java
@Component
@Slf4j
public class NotificationEventHandler {
    
    private final NotificationService notificationService;
    
    @EventListener
    @Async("notificationExecutor")
    public void on(UserRegisteredEvent event) {
        log.info("Sending user registration email to {}", event.getUserEmail());
        notificationService.sendUserRegistrationEmail(event);
    }
    
    @EventListener
    @Async("notificationExecutor")
    public void on(SubscriptionCreatedEvent event) {
        log.info("Sending subscription created email");
        notificationService.sendSubscriptionCreatedEmail(event);
    }
    
    @EventListener
    @Async("notificationExecutor")
    public void on(PlanUpgradedEvent event) {
        log.info("Sending plan upgrade email");
        notificationService.sendPlanUpgradedEmail(event);
    }
    
    @EventListener
    @Async("notificationExecutor")
    public void on(PaymentCapturedEvent event) {
        log.info("Sending payment confirmation email");
        notificationService.sendPaymentConfirmationEmail(event);
    }
    
    // ... other handlers
}

@Service
@Slf4j
public class NotificationService {
    
    public void sendUserRegistrationEmail(UserRegisteredEvent event) {
        try {
            EmailRequest emailRequest = new EmailRequest();
            emailRequest.setToEmail(event.getUserEmail());
            emailRequest.setToName(event.getFirstName());
            emailRequest.setTemplate("user-registered");
            emailRequest.setSubject("Welcome to MTBS");
            
            Map<String, Object> variables = new HashMap<>();
            variables.put("firstName", event.getFirstName());
            variables.put("activationToken", event.getActivationToken());
            variables.put("expiresInHours", 24);
            emailRequest.setVariables(variables);
            
            emailService.send(emailRequest);
            
            // Log delivery attempt
            notificationDeliveryLog.record(
                event.getTenantId(),
                "USER_REGISTERED",
                event.getUserEmail(),
                NotificationDeliveryStatus.SENT
            );
            
        } catch (Exception e) {
            log.error("Failed to send registration email: {}", e.getMessage());
            
            // Log failure
            notificationDeliveryLog.record(
                event.getTenantId(),
                "USER_REGISTERED",
                event.getUserEmail(),
                NotificationDeliveryStatus.FAILED,
                e.getMessage()
            );
            
            // Don't rethrow: notification failure shouldn't block main flow
            alerting.alertNotificationFailure(event, e);
        }
    }
}
```

---

## Notification Preferences

### User Opt-Out

```java
@Entity
public class NotificationPreference {
    @Id
    String userId;
    
    @Column
    Boolean emailOnPaymentReceived;  // true by default
    
    @Column
    Boolean emailOnUpgrade;  // true by default
    
    @Column
    Boolean emailOnDowngrade;  // true by default
    
    @Column
    Boolean emailOnInvoiceOverdue;  // true by default
    
    @Column
    Boolean emailMarketingDigest;  // false by default (opt-in)
}

@Service
public class NotificationService {
    
    public void sendPaymentConfirmationEmail(PaymentCapturedEvent event) {
        NotificationPreference prefs = preferencesRepository.findById(event.getUserId())
            .orElse(new NotificationPreference());
        
        if (!prefs.isEmailOnPaymentReceived()) {
            log.info("Payment email skipped: user opted out");
            return;
        }
        
        // Send email as usual
        emailService.send(/* ... */);
    }
}
```

---

## Unsubscribe Link

All notification emails include unsubscribe link:

```html
<footer>
  <p><a href="https://app.example.com/notifications/unsubscribe?token=[[${unsubscribeToken}]]&type=[[${eventType}]]">
    Unsubscribe from [[${eventType}]] emails
  </a></p>
</footer>
```

---

## Known Issues / Limitations

1. **Email Deliverability** — Even with SMTP, emails can be marked as spam. Solution: SPF/DKIM/DMARC records, monitor bounce rates.

2. **Template Maintenance** — Changing HTML templates requires careful testing (email clients render differently). Solution: Email template testing services (Litmus, Email on Acid).

3. **Unsubscribe Compliance** — List-Unsubscribe header required by some email providers (CAN-SPAM, GDPR). Solution: Implement unsubscribe mechanism, honor quickly.

4. **Rate Limiting** — Sending too many emails to same domain can trigger throttling. Solution: Batch sends, implement backoff.

---

## Future Improvements

1. **SMS Notifications** — Send text messages for critical events (payment failed, invoice overdue).

2. **In-App Notifications** — Dashboard notifications in addition to email.

3. **Notification Digest** — Batch daily/weekly digest instead of individual emails.

4. **Push Notifications** — Mobile app push notifications for time-sensitive events.

5. **Multi-Language Support** — Localize email templates based on user locale.

---

## Related Documents

- [domain-events.md](./domain-events.md) — General event publishing and handling
- [notification-module.md](./notification-module.md) — Notification system architecture
- [observability.md](./observability.md) — Logging notification events
