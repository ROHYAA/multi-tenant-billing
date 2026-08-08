---
version: 1.0
date: 2026-05-14
author: Manoj Pandi
status: Production Ready
tags:
  - api
  - business-invoices
  - invoicing
  - customers
  - payments
related_documents:
  - ./billing-api.md
  - ../04-business-modules/invoicing.md
  - ../07-data-model/entities.md
  - ./error-handling.md
---

# Business Invoices API

## Executive Summary

The Business Invoices API enables tenants to create, manage, and send invoices to their customers. Invoices progress through states (DRAFT → OPEN → PAID/VOID), support flexible line items (catalog products or custom), automatic tax calculation, PDF generation, and optional Razorpay payment links. All endpoints require `BILLING_MANAGE` permission.

---

## Context & Problem

### Business Invoice vs Platform Invoice

| Aspect | Platform Invoice | Business Invoice |
|--------|-----------------|------------------|
| **What it tracks** | What tenant owes platform | What customer owes tenant |
| **Created by** | BillingCycleJob (automatic) | User manually (via API) |
| **Triggered by** | Subscription renewal | User creates invoice endpoint |
| **Line items** | Subscription plan + overages | Customer products/services |
| **Recipient** | Tenant (owner/admin) | Tenant's customer |
| **Tax handling** | System-wide (GST) | Per-line-item (GST/regional) |
| **Payment** | Razorpay (automatic) | Manual or Razorpay link |

### Invoice Lifecycle

```
DRAFT
  ├─ Add/remove line items (no restrictions)
  ├─ Finalize → OPEN (set due date, freeze totals)
  │   │
  │   ├─ Send to customer (notification)
  │   │
  │   ├─ Record payment (1+ times for partial payments)
  │   │   ↓
  │   │ [Payments collected = total amount]
  │   │   ↓
  │   └─ PAID (auto-transition)
  │
  └─ Void → VOID (cancel before finalized)
```

---

## Dependencies

### Inbound (Who calls)

- **Frontend (React)** — Dashboard, customer invoicing page
- **Tenant admin** — Creates invoices for customers

### Outbound (What depends on)

- `BusinessInvoiceService` — Lifecycle state machine, line item management
- `BusinessPaymentService` — Payment recording, balance calculation
- `CustomerService` — Fetch customer details for invoice
- `ProductService` — Snapshot product pricing
- `InvoicePdfService` — PDF generation (Thymeleaf + Apache FOP)
- `RazorpayGateway` — Payment link creation
- `NotificationService` — Email customer when invoice sent

### Configuration

```yaml
business:
  invoice:
    pdf-enabled: true
    s3-bucket: ${AWS_S3_BUCKET_BUSINESS_INVOICES}
    default-currency: INR
    default-payment-terms-days: 30
  payment-methods:
    - CASH
    - BANK_TRANSFER
    - CHEQUE
    - RAZORPAY_LINK
```

---

## Endpoints

### POST /api/v1/business-invoices

**Purpose:** Create a new invoice in DRAFT status

**Request:**
```json
{
  "customerId": 42,
  "currency": "INR",
  "notes": "Invoice for March consulting services",
  "items": [
    {
      "productId": 1,
      "quantity": 5,
      "unitPrice": null,
      "taxPercentage": null
    },
    {
      "productId": null,
      "description": "Custom development - 10 hours @ 2000/hr",
      "quantity": 10,
      "unitPrice": 2000.00,
      "taxPercentage": 18.00
    }
  ]
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "invoiceNumber": "BINV-456-202605-0001",
    "customerId": 42,
    "customerName": "Acme Corp",
    "customerEmail": "billing@acme.com",
    "status": "DRAFT",
    "subtotal": 55000.00,
    "taxAmount": 9900.00,
    "totalAmount": 64900.00,
    "currency": "INR",
    "notes": "Invoice for March consulting services",
    "dueDate": null,
    "paidAt": null,
    "razorpayPaymentLinkId": null,
    "items": [
      {
        "id": 101,
        "description": "Professional Plan - Monthly",
        "quantity": 5,
        "unitPrice": 5000.00,
        "totalPrice": 25000.00,
        "taxPercentage": 18.00,
        "taxAmount": 4500.00
      },
      {
        "id": 102,
        "description": "Custom development - 10 hours @ 2000/hr",
        "quantity": 10,
        "unitPrice": 2000.00,
        "totalPrice": 20000.00,
        "taxPercentage": 18.00,
        "taxAmount": 3600.00
      },
      {
        "id": 103,
        "description": "18% GST",
        "quantity": 1,
        "unitPrice": 9900.00,
        "totalPrice": 9900.00,
        "taxPercentage": 0
      }
    ],
    "createdAt": "2026-05-14T14:30:00Z",
    "updatedAt": "2026-05-14T14:30:00Z"
  },
  "message": "Invoice created successfully"
}
```

**Line item semantics:**

| Scenario | Behavior |
|----------|----------|
| **productId provided** | `description`, `unitPrice`, `taxPercentage` from product; request values ignored |
| **productId null** | Must provide `description` and `unitPrice`; `taxPercentage` optional (default 0) |

**Calculation:**

```
Per line item:
  itemTax = quantity × unitPrice × (taxPercentage / 100)
  itemTotal = quantity × unitPrice + itemTax

Totals:
  subtotal = sum(quantity × unitPrice) across all items
  taxAmount = sum(itemTax) across all items
  totalAmount = subtotal + taxAmount
```

**Error codes:**
- `400` — Invalid customerId, no line items, negative amount
- `404` — Customer not found, product not found
- `403` — User lacks BILLING_MANAGE permission

---

### GET /api/v1/business-invoices

**Purpose:** List invoices with optional filtering

**Request:**
```
GET /api/v1/business-invoices?customerId=42&status=OPEN&page=0&size=20&sort=createdAt,desc
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "invoiceNumber": "BINV-456-202605-0001",
        "customerId": 42,
        "customerName": "Acme Corp",
        "status": "OPEN",
        "totalAmount": 64900.00,
        "currency": "INR",
        "dueDate": "2026-06-13T00:00:00Z",
        "paidAt": null,
        "createdAt": "2026-05-14T14:30:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "currentPage": 0,
    "pageSize": 20
  },
  "message": "Invoices fetched successfully"
}
```

**Filters:**
- `customerId` — Optional, filter by customer
- `status` — Optional, filter by status (DRAFT, OPEN, PAID, VOID)
- Default sort: `createdAt` DESC

---

### GET /api/v1/business-invoices/{id}

**Purpose:** Get single invoice with full details

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "invoiceNumber": "BINV-456-202605-0001",
    "customerId": 42,
    "customerName": "Acme Corp",
    "customerEmail": "billing@acme.com",
    "status": "OPEN",
    "subtotal": 55000.00,
    "taxAmount": 9900.00,
    "totalAmount": 64900.00,
    "currency": "INR",
    "notes": "Invoice for March consulting services",
    "dueDate": "2026-06-13T00:00:00Z",
    "paidAt": null,
    "razorpayPaymentLinkId": null,
    "items": [
      {
        "id": 101,
        "description": "Professional Plan - Monthly",
        "quantity": 5,
        "unitPrice": 5000.00,
        "totalPrice": 25000.00,
        "taxPercentage": 18.00,
        "taxAmount": 4500.00
      }
    ],
    "createdAt": "2026-05-14T14:30:00Z",
    "updatedAt": "2026-05-14T15:45:00Z"
  },
  "message": "Invoice fetched successfully"
}
```

---

### POST /api/v1/business-invoices/{id}/items

**Purpose:** Add a line item to a DRAFT invoice

**Request:**
```json
{
  "productId": 2,
  "quantity": 3
}
```

Or for custom item:

```json
{
  "description": "Training session - 4 hours",
  "quantity": 4,
  "unitPrice": 1500.00,
  "taxPercentage": 18.00
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "invoiceNumber": "BINV-456-202605-0001",
    "status": "DRAFT",
    "subtotal": 70000.00,
    "taxAmount": 12600.00,
    "totalAmount": 82600.00,
    "items": [
      {
        "id": 101,
        "description": "Professional Plan - Monthly",
        "quantity": 5,
        "unitPrice": 5000.00,
        "totalPrice": 25000.00
      },
      {
        "id": 102,
        "description": "Training session - 4 hours",
        "quantity": 4,
        "unitPrice": 1500.00,
        "totalPrice": 6000.00,
        "taxPercentage": 18.00,
        "taxAmount": 1080.00
      }
    ]
  },
  "message": "Line item added successfully"
}
```

**Preconditions:**
- Invoice status is DRAFT
- Either `productId` or (`description` + `unitPrice`) provided

**Error codes:**
- `400` — Invoice not in DRAFT status
- `400` — Neither productId nor custom description/price
- `404` — Product not found

---

### DELETE /api/v1/business-invoices/{id}/items/{itemId}

**Purpose:** Remove a line item from a DRAFT invoice

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "status": "DRAFT",
    "subtotal": 55000.00,
    "taxAmount": 9900.00,
    "totalAmount": 64900.00,
    "items": [
      {
        "id": 101,
        "description": "Professional Plan - Monthly",
        "quantity": 5,
        "unitPrice": 5000.00,
        "totalPrice": 25000.00
      }
    ]
  },
  "message": "Line item removed successfully"
}
```

**Preconditions:**
- Invoice status is DRAFT
- Item belongs to this invoice

**Totals recalculated** after removal

**Error codes:**
- `400` — Invoice not in DRAFT status
- `400` — Item doesn't belong to this invoice
- `404` — Item not found

---

### POST /api/v1/business-invoices/{id}/finalize

**Purpose:** Transition invoice from DRAFT to OPEN

**Request:** (empty body)

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "invoiceNumber": "BINV-456-202605-0001",
    "status": "OPEN",
    "totalAmount": 64900.00,
    "dueDate": "2026-06-13T00:00:00Z",
    "items": [...]
  },
  "message": "Invoice finalized successfully"
}
```

**Side effects:**
- Sets `status = OPEN`
- Sets `dueDate = NOW() + 30 days` (configurable)
- Freezes all totals (line items immutable until paid/void)
- Records in `AuditLog`

**Preconditions:**
- Invoice status is DRAFT
- Has at least 1 line item
- Total amount > 0

**Error codes:**
- `400` — Invoice not DRAFT
- `400` — No line items
- `400` — Total amount is 0

---

### POST /api/v1/business-invoices/{id}/send

**Purpose:** Send invoice to customer via email

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "invoiceNumber": "BINV-456-202605-0001",
    "status": "OPEN",
    "message": "Invoice sent to customer successfully"
  },
  "message": "Invoice sent to customer successfully"
}
```

**Side effects:**
- Publishes `BUSINESS_INVOICE_SENT` event
- NotificationService picks this up and emails customer PDF + payment link
- Records in `AuditLog`

**Preconditions:**
- Invoice status is OPEN (must be finalized first)

**Error codes:**
- `400` — Invoice not OPEN
- `500` — Email service error

---

### POST /api/v1/business-invoices/{id}/void

**Purpose:** Void an invoice (mark as cancelled)

**Request:** (empty body)

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "invoiceNumber": "BINV-456-202605-0001",
    "status": "VOID",
    "message": "Invoice voided successfully"
  },
  "message": "Invoice voided successfully"
}
```

**Side effects:**
- Sets `status = VOID`
- Cannot be undone
- Associated payment records remain (audit trail)
- Records in `AuditLog`

**Preconditions:**
- Invoice status is DRAFT or OPEN (not PAID, not already VOID)

**Error codes:**
- `400` — Cannot void PAID invoice (use payment refund instead)
- `400` — Invoice already VOID

---

### GET /api/v1/business-invoices/{id}/download

**Purpose:** Download invoice as PDF

**Response (200 OK):**
```
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="BINV-456-202605-0001.pdf"
Content-Length: 45823

[PDF binary data]
```

**Behavior:**
- Generates PDF using Thymeleaf template + Apache FOP
- Includes: invoice number, dates, customer details, line items, totals, tax breakdown
- Streamed directly to browser (not stored to S3)
- Filename: `{invoiceNumber}.pdf`

---

### POST /api/v1/business-invoices/{id}/payment-link

**Purpose:** Create Razorpay payment link for customer to pay online

**Response (200 OK):**
```json
{
  "success": true,
  "data": "plink_2c7P8k4x9M3z5q",
  "message": "Payment link created successfully"
}
```

**Behavior:**
- Creates a Razorpay Payment Link (hosted checkout)
- Idempotent — subsequent calls return existing link if already created
- Payment Link URL: `https://rzp.io/{linkId}`
- Share this URL with customer (short, shareable, no sensitive data)

**Side effects:**
- Sets `Invoice.razorpayPaymentLinkId`
- Webhook from Razorpay automatically records payment when customer pays

**Preconditions:**
- Invoice status is OPEN

**Error codes:**
- `400` — Invoice not OPEN
- `500` — Razorpay API error

---

### POST /api/v1/business-payments/{invoiceId}

**Purpose:** Record a payment received from customer

**Request:**
```json
{
  "amount": 32450.00,
  "method": "BANK_TRANSFER",
  "notes": "UTR 123456789",
  "paidAt": "2026-05-13T18:30:00Z"
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": 201,
    "invoiceId": 1,
    "amount": 32450.00,
    "method": "BANK_TRANSFER",
    "notes": "UTR 123456789",
    "paidAt": "2026-05-13T18:30:00Z",
    "createdAt": "2026-05-14T15:45:00Z"
  },
  "message": "Payment recorded successfully"
}
```

**Side effects:**
- Creates `BusinessPayment` record
- Calculates total collected: sum(all payments) for this invoice
- If total collected >= invoice total: auto-transitions `Invoice.status = PAID`
- Records in `AuditLog`

**Preconditions:**
- Invoice status is OPEN
- `amount` > 0
- `amount` ≤ outstanding balance
- Valid `method` from enum (CASH, BANK_TRANSFER, CHEQUE, RAZORPAY_LINK)

**Partial payments:**
- Call endpoint multiple times with different amounts
- Each call adds to total collected
- Invoice auto-transitions to PAID when sum reaches total

**Error codes:**
- `400` — Invoice not OPEN
- `400` — Amount exceeds outstanding balance
- `404` — Invoice not found

---

### GET /api/v1/business-payments/invoice/{invoiceId}

**Purpose:** List all payments recorded for an invoice

**Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "id": 201,
      "invoiceId": 1,
      "amount": 32450.00,
      "method": "BANK_TRANSFER",
      "notes": "UTR 123456789",
      "paidAt": "2026-05-13T18:30:00Z",
      "createdAt": "2026-05-14T14:30:00Z"
    },
    {
      "id": 202,
      "invoiceId": 1,
      "amount": 32450.00,
      "method": "RAZORPAY_LINK",
      "notes": null,
      "paidAt": "2026-05-14T15:45:00Z",
      "createdAt": "2026-05-14T15:45:00Z"
    }
  ],
  "message": "Payments fetched successfully"
}
```

**Use case:** Timeline display on invoice detail page showing all payment history

---

### GET /api/v1/business-payments/invoice/{invoiceId}/outstanding

**Purpose:** Get outstanding (remaining) balance for an invoice

**Response (200 OK):**
```json
{
  "success": true,
  "data": 0.00,
  "message": "Outstanding balance fetched successfully"
}
```

**Calculation:**

```
outstanding = invoice.totalAmount - sum(recorded payments)
```

**Use case:** Pre-fill amount field in "Record Payment" form

---

## State Machine

### Invoice Status Transitions

```
        ┌─────► OPEN ◄──────────┐
        │        │              │
        │        ├─ [Record payment] │
        │        │   Repeat 1+ times │
        │        │        │          │
    DRAFT        │   [Payments = Total]
        │        │        ▼          │
        │        └────► PAID         │
        │                           │
        └─────► VOID ◄─────────────┘
           [Cancel before finalize]
```

**Constraints:**
- Can only add/remove items in DRAFT
- Finalize (DRAFT → OPEN) is irreversible
- VOID only from DRAFT or OPEN
- Payment recording only in OPEN

---

## Usage Patterns

### Frontend: React + React Query

```typescript
// Create invoice
const useCreateInvoice = () => {
  return useMutation({
    mutationFn: (data) => apiClient.post('/api/v1/business-invoices', data),
    onSuccess: (response) => {
      setInvoiceId(response.data.id);
      showSuccess('Invoice created');
    }
  });
};

// Add line item
const useAddLineItem = (invoiceId) => {
  return useMutation({
    mutationFn: (item) =>
      apiClient.post(`/api/v1/business-invoices/${invoiceId}/items`, item),
    onSuccess: (response) => {
      setLineItems(response.data.items);
      showSuccess('Item added');
    }
  });
};

// Finalize invoice
const useFinalize = (invoiceId) => {
  return useMutation({
    mutationFn: () => apiClient.post(`/api/v1/business-invoices/${invoiceId}/finalize`),
    onSuccess: () => {
      showSuccess('Invoice finalized');
      navigate(`/invoices/${invoiceId}`);
    }
  });
};

// Get payment link
const usePaymentLink = (invoiceId) => {
  return useMutation({
    mutationFn: () => apiClient.post(`/api/v1/business-invoices/${invoiceId}/payment-link`),
    onSuccess: (response) => {
      const linkUrl = `https://rzp.io/${response.data}`;
      copyToClipboard(linkUrl);
      showSuccess('Payment link copied');
    }
  });
};

// Download PDF
const handleDownloadPdf = async (invoiceId) => {
  const response = await apiClient.get(
    `/api/v1/business-invoices/${invoiceId}/download`,
    { responseType: 'blob' }
  );
  
  const url = window.URL.createObjectURL(new Blob([response.data]));
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', 'invoice.pdf');
  document.body.appendChild(link);
  link.click();
  link.parentNode.removeChild(link);
};
```

### Backend: Service Implementation

```java
@Service
@RequiredArgsConstructor
@Transactional
public class BusinessInvoiceService {
    
    private final BusinessInvoiceRepository invoiceRepository;
    private final BusinessInvoiceItemRepository itemRepository;
    private final BusinessPaymentRepository paymentRepository;
    private final ProductService productService;
    
    public BusinessInvoiceResponse create(CreateBusinessInvoiceRequest request) {
        // 1. Validate customer exists
        Customer customer = customerRepository.findById(request.getCustomerId())
            .orElseThrow(() -> ResourceException.notFound("Customer"));
        
        // 2. Create invoice
        BusinessInvoice invoice = BusinessInvoice.builder()
            .invoiceNumber(generateInvoiceNumber())
            .customerId(request.getCustomerId())
            .status(InvoiceStatus.DRAFT)
            .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
            .notes(request.getNotes())
            .subtotal(BigDecimal.ZERO)
            .taxAmount(BigDecimal.ZERO)
            .totalAmount(BigDecimal.ZERO)
            .build();
        invoiceRepository.save(invoice);
        
        // 3. Add line items
        for (var itemReq : request.getItems()) {
            addLineItemInternal(invoice, itemReq);
        }
        
        // 4. Recalculate totals
        recalculateTotals(invoice);
        invoiceRepository.save(invoice);
        
        return toResponse(invoice);
    }
    
    public BusinessInvoiceResponse finalize(Long invoiceId) {
        BusinessInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow();
        
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw BillingException.invalidState("Can only finalize DRAFT invoices");
        }
        
        if (invoice.getItems().isEmpty()) {
            throw BillingException.invalidState("Cannot finalize invoice without line items");
        }
        
        // Set due date to 30 days from now
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setDueDate(Instant.now().plus(Duration.ofDays(30)));
        invoiceRepository.save(invoice);
        
        applicationEventPublisher.publishEvent(
            new BusinessInvoiceFinalizedEvent(invoice));
        
        return toResponse(invoice);
    }
    
    public void recordPayment(Long invoiceId, RecordPaymentRequest request) {
        BusinessInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow();
        
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw BillingException.invalidState("Invoice not OPEN");
        }
        
        BigDecimal outstanding = calculateOutstanding(invoice);
        if (request.getAmount().compareTo(outstanding) > 0) {
            throw BillingException.paymentError("Amount exceeds outstanding balance");
        }
        
        // Record payment
        BusinessPayment payment = BusinessPayment.builder()
            .invoiceId(invoiceId)
            .amount(request.getAmount())
            .method(request.getMethod())
            .notes(request.getNotes())
            .paidAt(request.getPaidAt())
            .build();
        paymentRepository.save(payment);
        
        // Check if fully paid
        BigDecimal totalCollected = paymentRepository
            .findByInvoiceId(invoiceId)
            .stream()
            .map(BusinessPayment::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        if (totalCollected.compareTo(invoice.getTotalAmount()) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidAt(Instant.now());
            invoiceRepository.save(invoice);
            
            applicationEventPublisher.publishEvent(
                new BusinessInvoicePaidEvent(invoice));
        }
    }
    
    private void recalculateTotals(BusinessInvoice invoice) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxAmount = BigDecimal.ZERO;
        
        for (var item : invoice.getItems()) {
            BigDecimal lineTotal = item.getQuantity().multiply(item.getUnitPrice());
            BigDecimal lineTax = lineTotal.multiply(
                item.getTaxPercentage().divide(BigDecimal.valueOf(100)));
            
            subtotal = subtotal.add(lineTotal);
            taxAmount = taxAmount.add(lineTax);
        }
        
        invoice.setSubtotal(subtotal);
        invoice.setTaxAmount(taxAmount);
        invoice.setTotalAmount(subtotal.add(taxAmount));
    }
}
```

---

## Error Handling

See [error-handling.md](./error-handling.md#business-error-codes) for complete error list.

**Common errors:**

| Error Code | HTTP | Message | Cause |
|------------|------|---------|-------|
| `BIZ_INV_001` | 400 | Cannot modify OPEN invoice | Line items immutable once finalized |
| `BIZ_INV_002` | 400 | Invoice has no line items | Cannot finalize empty invoice |
| `BIZ_INV_003` | 400 | Cannot void PAID invoice | Use payment refund instead |
| `BIZ_PAY_001` | 400 | Payment exceeds outstanding | Amount > remaining balance |
| `BIZ_PAY_002` | 400 | Invoice not OPEN | Can only record payments for OPEN invoices |

---

## Summary

| Feature | Mechanism | Example |
|---------|-----------|---------|
| Line items | Products or custom | Item by ID or custom description + price |
| Tax calculation | Per-line-item | 18% GST on each item |
| State machine | DRAFT → OPEN → PAID/VOID | No reversals, immutable once OPEN |
| Partial payments | Multiple recording calls | ₹32,450 + ₹32,450 = ₹64,900 PAID |
| PDF generation | Thymeleaf template | Download or email to customer |
| Payment links | Razorpay hosted | Share short URL for online payment |

