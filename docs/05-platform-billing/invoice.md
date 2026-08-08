---
version: 1.0
date: 2026-04-15
author: Manoj Pandi
status: Production Ready
tags:
  - billing
  - domain
  - invoice
  - platform-billing
  - payment
  - subscription
  - line-items
related_documents:
  - ./subscription-lifecycle.md
  - ./payment-processing.md
  - ../07-data-model/entities.md
---

# Invoice

## Executive Summary

An **invoice** is MTBS's core billing domain object. It represents a tenant's subscription charge for a billing period (e.g., monthly or annual). Invoices are generated automatically by `BillingCycleJob` on a scheduled basis, contain line items (subscription charge, taxes, discounts), and flow through states: DRAFT → OPEN → PAID/VOID. The invoice is the source of truth for billing history, payment tracking, and financial reporting. When an invoice is generated, created, or paid, domain events are published to notify subscribers (notification, audit, analytics). Tenants retrieve invoices via REST API with pagination and can download PDFs for accounting.

---

## Context / Problem

### Why Invoices as a Core Entity?

Without invoices, billing would be ad-hoc queries over subscriptions:
- "What did tenant 456 owe last month?" — Would requiring computing charges with proration logic
- "How much did customer pay in 2025?" — Requires aggregating many transactions

With invoices as the canonical record:
- "What was invoice #INV-2026-00100?" — Query single row, get all details (amount, period, status, payment date)
- Audit trail is built-in (created_at, updated_at, paid_at)
- Financial reporting is simple (sum PAID invoices for revenue)
- Compliance: invoices are immutable records (soft-deletes only, never truly deleted)

### Why Line Items?

A single invoice might contain multiple charges:
- Base subscription fee (e.g., $99/month)
- Proration (partial month, e.g., +$14.29)
- Overage (exceeded usage limits, e.g., +$25.00)
- Discount (promotional, e.g., -$10.00)
- Tax (computed as 18% of subtotal)

Without line items, we'd only know the total amount; auditors couldn't trace where it came from. Line items provide transparency.

### Why Invoice States (DRAFT → OPEN → PAID)?

```
DRAFT:    Generated but not finalized; can be edited
OPEN:     Finalized; payment due date set; customer notified
PAID:     Payment received; due date passed
OVERDUE:  Past due date; no payment received; reminder sent
VOID:     Cancelled (e.g., plan downgrade mid-period)
FAILED:   Payment attempts failed; retry needed
```

States enforce a lifecycle: you cannot mark a DRAFT invoice as PAID (must go through OPEN first). This prevents accounting errors.

---

## Dependencies

### Inbound (Who Creates/Updates Invoices)
- `BillingCycleJob` (scheduled) → `InvoiceService.generateInvoice()` — Generate invoices for billing period
- `BillingCycleJob` → `InvoiceService.finalizeInvoice()` — Move DRAFT to OPEN
- `PaymentService` → `InvoiceService.markInvoicePaid()` — Mark PAID after payment captured
- `SubscriptionService` → `InvoiceService.voidInvoice()` — Void on plan downgrade
- `RazorpayWebhookController` → `PaymentService.handlePaymentWebhook()` → mark paid

### Outbound (Who Queries Invoices)
- `InvoiceController.listInvoices()` — HTTP GET /api/invoices
- `InvoiceController.getInvoiceById()` — HTTP GET /api/invoices/{id}
- `InvoicePdfService.generatePdf()` — Generate PDF for download
- `TenantBillingDashboardService` — Fetch recent invoices for dashboard
- Audit service — Query invoices for financial reporting
- Notification service — Fetch invoice details for email

### Configuration
- `billing.invoice-prefix: INV` — Invoice number prefix
- `billing.invoice-due-date-days: 7` — Payment due after 7 days
- `billing.invoice-retry-days: 14` — Retry payment after 14 days
- `billing.tax-rate: 0.18` — Tax rate (18% for India)

---

## Design / Implementation

### Invoice Entity

```java
@Entity
@Table(name = "invoices")
public class Invoice extends AuditableEntity {
    
    // Identification
    @Column(unique = true)
    private String invoiceNumber;       // "INV-2026-00100"
    
    // References
    private Long subscriptionId;        // Which subscription this invoice is for
    
    // State + Dates
    @Enumerated(EnumType.STRING)
    private InvoiceStatus status;       // DRAFT, OPEN, PAID, VOID
    private Instant dueDate;            // When payment is due
    private Instant paidAt;             // When payment was received
    private Instant billingPeriodStart; // Period covered by invoice
    private Instant billingPeriodEnd;
    
    // Amounts
    private BigDecimal subtotal;        // Sum of line items (before tax)
    private BigDecimal taxAmount;       // Computed tax
    private BigDecimal discountAmount;  // Promotional discount
    private BigDecimal totalAmount;     // subtotal + tax - discount
    private String currency;            // "INR", "USD"
    
    // Integration
    private String razorpayInvoiceId;   // Razorpay reference for webhook mapping
    private String pdfUrl;              // S3 link after PDF generated
    
    // Relationships
    @OneToMany(mappedBy = "invoice")
    private List<InvoiceLineItem> lineItems;
}
```

### Invoice Lifecycle

```
CREATE (Subscription Active)
  ↓ BillingCycleJob.execute() [daily at midnight]
  │
DRAFT (Invoice Generated)
  ├─ invoiceNumber = generateInvoiceNumber()
  ├─ status = DRAFT
  ├─ subtotal = subscription.plan.monthlyPrice or annualPrice
  ├─ taxAmount = subtotal * 0.18
  ├─ totalAmount = subtotal + tax - discount
  ├─ lineItems.add(SubscriptionLineItem(description="Pro Plan — Monthly"))
  ├─ created_at = NOW()
  ├─ Publish: InvoiceGeneratedEvent
  │
OPEN (Invoice Finalized)
  ├─ status = OPEN
  ├─ dueDate = createdAt + 7 days
  ├─ Publish: InvoiceOpenedEvent
  ├─ NotificationService sends: "Invoice #INV-2026-00100 due by May 1"
  │
[OPEN → PAID or OPEN → OVERDUE or OPEN → VOID]
  │
PAID (Payment Received)
  ├─ PaymentService.handleWebhook(razorpayPaymentId)
  ├─ Verify Razorpay signature
  ├─ UPDATE invoices SET status=PAID, paidAt=NOW()
  ├─ DELETE outstanding invoice (moved to history)
  ├─ Publish: InvoicePaidEvent
  ├─ NotificationService sends: "Payment received for invoice #..."
  │
VOID (Cancelled)
  ├─ SubscriptionService.downgradePlan()
  ├─ Proration might void mid-period invoice
  ├─ status = VOID
  ├─ Publish: InvoiceVoidedEvent
```

### Invoice Number Generation

**Strategy**: Sequential, globally unique, human-readable.

```
Prefix: "INV"
Date: YYYY-MM (e.g., 2026-04)
Sequence: 00001, 00002, ...

Format: INV-2026-04-00001

Example:
  Invoice 1 (created April 1, 2026): INV-2026-04-00001
  Invoice 2 (created April 5, 2026): INV-2026-04-00002
  Invoice 1 (created May 1, 2026):   INV-2026-05-00001 (sequence resets per month)
```

**Implementation**:
```java
private String generateInvoiceNumber() {
    String yearMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    
    long count = invoiceRepository.countByInvoiceNumberStartingWith("INV-" + yearMonth);
    String sequence = String.format("%05d", count + 1);
    
    return "INV-" + yearMonth + "-" + sequence;
}
```

### Line Items

```java
@Entity
@Table(name = "invoice_line_items")
public class InvoiceLineItem {
    
    private Long invoiceId;           // FK to Invoice
    private String description;       // "Pro Plan — Monthly"
    private BigDecimal quantity;      // Number of units (usually 1)
    private BigDecimal unitPrice;     // Price per unit
    private BigDecimal totalPrice;    // quantity * unitPrice
    private LineItemType type;        // SUBSCRIPTION, OVERAGE, DISCOUNT, TAX
}
```

**Example line items for a single invoice:**

| Type | Description | Quantity | Unit Price | Total Price |
|------|-------------|----------|-----------|------------|
| SUBSCRIPTION | Pro Plan — Monthly | 1 | 999.00 | 999.00 |
| OVERAGE | Usage limit exceeded (API calls) | 500 | 0.05 | 25.00 |
| DISCOUNT | Q1 2026 Promo (15%) | 1 | -151.50 | -151.50 |
| TAX | GST (18%) | 1 | 172.65 | 172.65 |
| | | | **Subtotal** | **999.00** |
| | | | **Tax** | **172.65** |
| | | | **Discount** | **-151.50** |
| | | | **Total** | **1,020.15** |

### Invoice Payment Flow

```
Invoice Status: OPEN + dueDate passed
  ↓ SubscriptionService.initiatePayment()
  ├─ Create Razorpay Order (amount, order_id=invoiceId, receipt=invoiceNumber)
  ├─ Return order details to frontend
  ├─ Frontend displays: "Pay ₹1,020 for INV-2026-04-00001"
  ├─ User completes payment on Razorpay checkout
  │
Razorpay Webhook: payment.authorized
  ├─ POST /api/webhooks/razorpay
  ├─ Verify HMAC signature
  ├─ Extract: payment_id, order_id (invoiceId), amount, status
  ├─ PaymentService.capturePayment(paymentId, invoiceId)
  ├─ Razorpay captures funds
  ├─ UPDATE invoices SET status=PAID, paidAt=NOW()
  ├─ Publish: PaymentCapturedEvent, InvoicePaidEvent
  ├─ NotificationService sends: "Payment confirmed"
  ├─ HTTP 200 OK to Razorpay
```

### API Endpoints

```
GET /api/v1/invoices
  Required: BILLING_MANAGE permission
  Pagination: page, size, sort
  Returns: List[InvoiceResponse]
  
  Example:
  GET /api/v1/invoices?page=0&size=10&sort=createdAt,desc
  [
    {
      "id": 100,
      "invoiceNumber": "INV-2026-04-00001",
      "status": "PAID",
      "amount": 1020.15,
      "currency": "INR",
      "dueDate": "2026-05-01T00:00:00Z",
      "paidAt": "2026-04-28T10:30:00Z",
      "billingPeriodStart": "2026-04-01T00:00:00Z",
      "billingPeriodEnd": "2026-05-01T00:00:00Z",
      "lineItems": [...]
    }
  ]

GET /api/v1/invoices/{id}
  Required: BILLING_MANAGE permission
  Returns: InvoiceResponse (with full line items + details)

POST /api/v1/invoices/{id}/void
  Required: BILLING_MANAGE permission
  Voids a DRAFT or OPEN invoice
  Returns: InvoiceResponse (status=VOID)

GET /api/v1/invoices/{id}/download
  Required: BILLING_MANAGE permission
  Returns: PDF file (application/pdf, attachment)
  Content-Disposition: attachment; filename="INV-2026-04-00001.pdf"
```

### Domain Events

**InvoiceGeneratedEvent** (when DRAFT invoice created)
```json
{
  "eventType": "INVOICE_GENERATED",
  "invoiceId": 100,
  "invoiceNumber": "INV-2026-04-00001",
  "subscriptionId": 789,
  "amount": 1020.15,
  "currency": "INR",
  "dueDate": "2026-05-01",
  "tenantId": 456,
  "tenantName": "ACME Inc."
}
```

Listeners: NotificationListener (enqueue invoice email), AuditListener (log creation), AnalyticsListener (track invoice count)

**InvoicePaidEvent** (when payment received)
```json
{
  "eventType": "INVOICE_PAID",
  "invoiceId": 100,
  "invoiceNumber": "INV-2026-04-00001",
  "amount": 1020.15,
  "paidAt": "2026-04-28T10:30:00Z",
  "paymentId": "pay_KjL456m789nOp",
  "tenantId": 456
}
```

Listeners: NotificationListener (send receipt email), AccountingService (update revenue), Refund Service (trigger refunds if overpaid)

---

## Flow

See Invoice Lifecycle ASCII art above.

---

## Code References

| Class | Tag | Method | Purpose |
|-------|-----|--------|---------|
| `Invoice` | [BIL-2] | Entity mapping | Domain object; stored in invoices table |
| `InvoiceLineItem` | [BIL-3] | Entity mapping | Line item child of Invoice |
| `InvoiceService` | [BIL-15] | `generateInvoice()` | Create DRAFT invoice for subscription + period |
| `InvoiceService` | [BIL-15] | `finalizeInvoice()` | Move DRAFT → OPEN; set due date |
| `InvoiceService` | [BIL-15] | `markInvoicePaid()` | Move OPEN → PAID after payment captured |
| `InvoiceService` | [BIL-15] | `voidInvoice()` | Cancel invoice; move any → VOID |
| `InvoiceController` | [BIL-41] | `listInvoices()` | HTTP GET /api/invoices |
| `InvoiceController` | [BIL-41] | `getInvoiceById()` | HTTP GET /api/invoices/{id} |
| `InvoiceResponse` | [BIL-46] | DTO | API response shape |

---

## Rules / Constraints

1. **Invoice numbers MUST be globally unique within a year** — Allows customer-facing invoice references. Format: INV-YYYY-MM-NNNNN. Duplicate numbers cause financial audit failures.

2. **invoiceStatus transitions are one-directional** — DRAFT → OPEN → (PAID or VOID). Never reverse (PAID → OPEN). Prevents accounting confusion. Use voidInvoice() to cancel.

3. **Total amount MUST equal (subtotal + tax - discount)** — Always validate on save. Compute in this exact order to avoid rounding errors with BigDecimal.

4. **Line items MUST NOT be modified after invoice is OPEN** — DRAFT invoices can be edited (rare). Once OPEN, line items are locked. This prevents retroactive charge changes.

5. **Tax rate is computed from tenant's jurisdiction** — Currently all tenants use 18% (India GST). Future: multi-country support, per-tenant tax rate in Plan or Tenant entity.

6. **Invoice record MUST NEVER be deleted** — Only soft-delete via status=VOID. Invoices are permanent audit records for financial compliance (GDPR right-to-be-forgotten does not apply to billing).

7. **Currency MUST match subscription plan currency** — Invoice.currency = Subscription.Plan.currency. Prevents currency mismatches during accounting.

---

## Failure Scenarios

| Scenario | Exception Class | HTTP Status | Recovery |
|----------|-----------------|-------------|----------|
| Subscription not found (on invoice generation) | `ResourceException.notFound()` | 500 (internal scheduler) | Check subscription exists before scheduled job; log alert |
| Invoice number generation race condition | Concurrent INS violates unique constraint | 500 (retried next cycle) | Db unique constraint + retry mechanism handles |
| Invalid line item totals (rounding error) | `ValidationException` on save | 500 (scheduler) | Use BigDecimal correctly; test rounding in unit tests |
| Plan not found (when fetching price) | `ResourceException.notFound()` | 500 (scheduler) | Subscription should have valid planId; check data integrity |
| Payment webhook processing fails | `WebhookException` | 202 Accepted (webhook retry by Razorpay) | Fix issue; Razorpay retries webhook; manual intervention if needed |
| Invoice PDF generation fails (S3 down) | `S3Exception` | 500 (user requests PDF) | S3 is down; retry endpoint after S3 recovery |
| Void invoice that's already paid | `ValidationException` (illegal state) | 400 Bad Request | User cannot void paid invoice; show error message |
| Download PDF before it's generated | PDF file not found in S3 | 404 Not Found | Invoice is DRAFT; must be OPEN first; regenerate PDF |

---

## Edge Cases

- **Concurrency**: Multiple processes simultaneously mark same invoice as PAID. Database row-level lock + optimistic locking (version field) prevents double-payment.

- **Timezone**: Invoice dates (created_at, dueDate, paidAt) are all TIMESTAMPTZ (UTC). Tenant's timezone is stored separately; API returns UTC timestamps. Frontend converts to tenant's local time.

- **Leap seconds**: Invoice generation happens at midnight UTC daily. Leap second (rare) is handled by OS/database; no special logic needed.

- **Large invoices**: Invoice line items can be hundreds (overages for many services). API pagination + database query indexes handle this.

- **Partial payments**: Razorpay supports partial payment capture. If tenant pays $500 of $1,000 invoice, payment is captured but invoice stays OPEN. Manual intervention needed (not yet implemented).

- **Duplicate webhooks**: Razorpay webhook might fire twice for same payment. Idempotency check (payment_id + invoice_id combination) prevents double-charging.

---

## Known Issues / Limitations

1. **No tax calculation per item** — Tax is computed on total subtotal only. If future feature adds item-level taxes (e.g., some items 0% tax, others 18%), requires redesign.

2. **No invoice amendments** — If you need to modify an OPEN invoice (e.g., add another charge), you must void it and create a new one. No credit memo support yet.

3. **No multi-currency handling** — All invoices in INR by design. Supporting USD, EUR requires code changes in: plan prices, payment gateway config, tax rates per currency.

4. **No scheduled payment retries** — If payment fails, manual retry is needed. Automated retry on subsequent billing cycle is not yet implemented.

5. **PDF generation is slow** — First-time PDF generation (Thymeleaf to PDF via Flying Saucer) takes 2-3 seconds. Cached PDFs are fast; miss causes latency.

6. **No prorating partial months** — Subscription proration is not yet fully implemented in invoice line items. Currently all invoices are for full periods.

---

## Future Improvements

1. Implement invoice amendments (credit memos) — Allow adding line items to invoices without voiding.

2. Add multi-currency support — Tax rates, payment amounts per currency. Requires accounting system changes.

3. Implement scheduled payment retries — If payment fails, OutboxEventProcessor retries on day 3, 7, 14. Automatic dunning management.

4. Add invoice templates — Allow tenants to customize invoice appearance (logo, terms, tax ID).

5. Implement bulk invoice operations — Void multiple invoices by date range. Re-send invoices to specific subscriptions. Update batch payments (SEPA, ACH).

6. Add financial reporting API — Endpoint to query revenue by date range, customer, product with breakdown by period.

---

## Related Documents
- [subscription-lifecycle.md](./subscription-lifecycle.md) — Subscriptions that invoices bill for
- [payment-processing.md](./payment-processing.md) — Payment capture and settlement (Razorpay integration)
- [outbox-pattern.md](../01-architecture/outbox-pattern.md) — Event persistence
- [event-flow.md](../01-architecture/event-flow.md) — InvoiceGeneratedEvent, InvoicePaidEvent listeners
- [proration.md](./proration.md) — Mid-period plan changes and prorating
- [plan-change-flow.md](./plan-change-flow.md) — Upgrade/downgrade logic that creates invoices
- [system-design.md](../01-architecture/system-design.md) — Architecture overview
