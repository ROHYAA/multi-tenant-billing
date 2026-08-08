---
version: 1.0
date: 2026-05-14
author: Manoj Pandi
status: Production Ready
tags:
  - api
  - business-payments
  - payment-recording
  - payment-methods
  - reconciliation
related_documents:
  - ./business-invoices.md
  - ../04-business-modules/invoicing.md
  - ./error-handling.md
---

# Business Payments API

## Executive Summary

The Business Payments API enables recording payments from customers against business invoices. Supports multiple payment methods (cash, bank transfer, cheque, Razorpay), partial payments, balance tracking, and automatic invoice state transitions. Designed for manual payment reconciliation and flexible offline payment workflows.

---

## Context & Problem

### Payment Workflows

MTBS supports three payment workflows:

| Workflow | Creation | Recording | State Transition |
|----------|----------|-----------|------------------|
| **Razorpay Link** | Tenant generates link | Automatic webhook | Invoice → PAID on success |
| **Manual/Offline** | Customer pays (bank, cheque) | Tenant records via API | Invoice → PAID when sum = total |
| **Partial Payments** | N/A | Multiple API calls | Accumulated balance tracking |

### Partial Payments Example

```
Invoice Total: ₹64,900
Payment 1: ₹32,450 (50% upfront)  → Outstanding: ₹32,450
Payment 2: ₹32,450 (balance)      → Outstanding: ₹0 → Invoice PAID
```

---

## Dependencies

### Inbound

- **Frontend (React)** — Payment reconciliation dashboard
- **Razorpay webhook** — Automatic payment recording on link payment

### Outbound

- `BusinessInvoiceService` — Fetch invoice, update status
- `BusinessPaymentRepository` — Persistence
- `PaymentGatewayPort` — Razorpay payment link creation
- `OutboxEventPublisher` — Event publishing for notifications

### Configuration

```yaml
business:
  payment:
    allowed-methods:
      - CASH
      - BANK_TRANSFER
      - CHEQUE
      - RAZORPAY_LINK
    razorpay:
      enabled: true
      key-id: ${RAZORPAY_KEY_ID}
      key-secret: ${RAZORPAY_KEY_SECRET}
```

---

## Endpoints

### POST /api/v1/business-payments/{invoiceId}

**Purpose:** Record a payment received from a customer

**Request:**
```json
{
  "amount": 32450.00,
  "method": "BANK_TRANSFER",
  "notes": "UTR 123456789 dated 2026-05-13",
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
    "currency": "INR",
    "method": "BANK_TRANSFER",
    "notes": "UTR 123456789 dated 2026-05-13",
    "paidAt": "2026-05-13T18:30:00Z",
    "razorpayPaymentLinkId": null,
    "createdAt": "2026-05-14T15:45:00Z"
  },
  "message": "Payment recorded successfully"
}
```

**Payment Methods:**

| Method | Notes | Use Case |
|--------|-------|----------|
| `CASH` | No reference | Walk-in payment, field collection |
| `BANK_TRANSFER` | UTR (Unique Transaction Reference) | Digital payment, B2B |
| `CHEQUE` | Cheque number + date | Traditional payment |
| `RAZORPAY_LINK` | Automatic via webhook | Online payment via link |

**Behavior:**

- Calculates outstanding balance: `invoice.totalAmount - sum(previous payments)`
- Validates amount ≤ outstanding balance
- Stores payment record with `paidAt` timestamp
- **If sum(all payments) >= invoice.totalAmount:**
  - Sets `Invoice.status = PAID`
  - Sets `Invoice.paidAt = NOW()`
  - Publishes `BUSINESS_INVOICE_PAID` event
  - Triggers notification to customer (email receipt)

**Backdating:**

```
// Record offline payment from past
POST /api/v1/business-payments/1
{
  "amount": 32450.00,
  "method": "CHEQUE",
  "notes": "Cheque #42 dated 2026-05-01",
  "paidAt": "2026-05-01T10:00:00Z"    ← Payment date ≠ Today
}
```

- `paidAt` can be in the past (for offline payments recorded late)
- `createdAt` is always NOW()
- Use for reconciling past payments

**Preconditions:**
- Invoice status is OPEN
- `amount` > 0
- `amount` ≤ outstanding balance
- Valid `method` from enum

**Error codes:**
- `400` — Invoice not OPEN
- `400` — Amount ≤ 0
- `400` — Amount exceeds outstanding
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
      "currency": "INR",
      "method": "BANK_TRANSFER",
      "notes": "UTR 123456789",
      "paidAt": "2026-05-13T18:30:00Z",
      "razorpayPaymentLinkId": null,
      "createdAt": "2026-05-14T14:30:00Z"
    },
    {
      "id": 202,
      "invoiceId": 1,
      "amount": 32450.00,
      "currency": "INR",
      "method": "BANK_TRANSFER",
      "notes": "UTR 987654321",
      "paidAt": "2026-05-14T09:15:00Z",
      "razorpayPaymentLinkId": null,
      "createdAt": "2026-05-14T15:45:00Z"
    }
  ],
  "message": "Payments fetched successfully"
}
```

**Timeline display:**

```
Invoice: BINV-456-202605-0001 (Total: ₹64,900)

Payment History:
  2026-05-13 18:30  |  ₹32,450  |  BANK_TRANSFER  |  UTR 123456789
  2026-05-14 09:15  |  ₹32,450  |  BANK_TRANSFER  |  UTR 987654321
  ───────────────────────────────────────────────────
  Total Received:   ₹64,900    Status: PAID
```

**Use case:** Show payment history on invoice detail view, track collection status

---

### GET /api/v1/business-payments/invoice/{invoiceId}/outstanding

**Purpose:** Get remaining balance for an invoice

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

Examples:
  Invoice: ₹64,900
  Payments: ₹32,450 + ₹32,450 = ₹64,900
  Outstanding: ₹0.00
  
  Invoice: ₹64,900
  Payments: ₹32,450
  Outstanding: ₹32,450.00
```

**Use case:**
- Pre-fill "Amount" field in payment form
- Display balance due on invoice
- Check if payment required before sending invoice

---

## Integration Patterns

### Frontend: Recording Payment

```typescript
// Get outstanding balance
const useOutstandingBalance = (invoiceId) => {
  return useQuery({
    queryKey: ['outstanding', invoiceId],
    queryFn: () => apiClient.get(
      `/api/v1/business-payments/invoice/${invoiceId}/outstanding`
    )
  });
};

// Record payment
const useRecordPayment = (invoiceId) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payment) =>
      apiClient.post(`/api/v1/business-payments/${invoiceId}`, payment),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['outstanding', invoiceId] });
      queryClient.invalidateQueries({ queryKey: ['payments', invoiceId] });
      showSuccess('Payment recorded');
    }
  });
};

// Payment form component
function RecordPaymentForm({ invoiceId }) {
  const { data: outstanding } = useOutstandingBalance(invoiceId);
  const { mutate: recordPayment } = useRecordPayment(invoiceId);
  
  const [form, setForm] = useState({
    amount: outstanding?.data || 0,
    method: 'BANK_TRANSFER',
    notes: '',
    paidAt: new Date().toISOString().split('T')[0]
  });

  const handleSubmit = () => {
    recordPayment({
      amount: form.amount,
      method: form.method,
      notes: form.notes,
      paidAt: form.paidAt + 'T00:00:00Z'
    });
  };

  return (
    <form onSubmit={handleSubmit}>
      <label>Amount Due: ₹{outstanding?.data?.toFixed(2)}</label>
      <input
        type="number"
        value={form.amount}
        onChange={(e) => setForm({ ...form, amount: parseFloat(e.target.value) })}
        max={outstanding?.data}
        step="0.01"
      />
      
      <select
        value={form.method}
        onChange={(e) => setForm({ ...form, method: e.target.value })}
      >
        <option value="CASH">Cash</option>
        <option value="BANK_TRANSFER">Bank Transfer</option>
        <option value="CHEQUE">Cheque</option>
        <option value="RAZORPAY_LINK">Razorpay Link</option>
      </select>

      <input
        type="text"
        placeholder="UTR / Cheque #"
        value={form.notes}
        onChange={(e) => setForm({ ...form, notes: e.target.value })}
      />

      <input
        type="date"
        value={form.paidAt}
        onChange={(e) => setForm({ ...form, paidAt: e.target.value })}
      />

      <button type="submit">Record Payment</button>
    </form>
  );
}
```

### Payment Timeline Component

```typescript
function PaymentTimeline({ invoiceId }) {
  const { data: payments } = useQuery({
    queryKey: ['payments', invoiceId],
    queryFn: () => apiClient.get(`/api/v1/business-payments/invoice/${invoiceId}`)
  });

  const totalPaid = payments?.data?.reduce((sum, p) => sum + p.amount, 0) || 0;

  return (
    <div className="payment-timeline">
      <h3>Payment History</h3>
      <div className="timeline">
        {payments?.data?.map((payment) => (
          <div key={payment.id} className="timeline-item">
            <div className="date">{new Date(payment.paidAt).toLocaleDateString()}</div>
            <div className="amount">₹{payment.amount.toFixed(2)}</div>
            <div className="method">{payment.method}</div>
            {payment.notes && <div className="notes">{payment.notes}</div>}
          </div>
        ))}
      </div>
      <div className="total-paid">
        <strong>Total Paid: ₹{totalPaid.toFixed(2)}</strong>
      </div>
    </div>
  );
}
```

### Backend: Service Implementation

```java
@Service
@RequiredArgsConstructor
@Transactional
public class BusinessPaymentService {
    
    private final BusinessPaymentRepository paymentRepository;
    private final BusinessInvoiceService invoiceService;
    private final OutboxEventPublisher outboxEventPublisher;
    
    public BusinessPaymentResponse record(Long invoiceId, RecordPaymentRequest request) {
        // 1. Fetch invoice
        BusinessInvoice invoice = invoiceService.getEntityById(invoiceId);
        
        // 2. Validate state
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw BillingException.invalidState(
                "Payments can only be recorded for OPEN invoices");
        }
        
        // 3. Validate amount
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BillingException.validation("Amount must be > 0");
        }
        
        // 4. Calculate outstanding balance
        BigDecimal alreadyPaid = paymentRepository
            .findByInvoiceId(invoiceId)
            .stream()
            .map(BusinessPayment::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal outstanding = invoice.getTotalAmount().subtract(alreadyPaid);
        
        // 5. Validate amount <= outstanding
        if (request.getAmount().compareTo(outstanding) > 0) {
            throw BillingException.validation(
                String.format("Amount ₹%.2f exceeds outstanding ₹%.2f",
                    request.getAmount(), outstanding));
        }
        
        // 6. Create payment record
        BusinessPayment payment = BusinessPayment.builder()
            .invoiceId(invoiceId)
            .amount(request.getAmount())
            .currency(invoice.getCurrency())
            .method(request.getMethod())
            .notes(request.getNotes())
            .paidAt(request.getPaidAt() != null 
                ? request.getPaidAt() 
                : Instant.now())
            .build();
        
        paymentRepository.save(payment);
        
        // 7. Check if fully paid
        BigDecimal totalPaid = alreadyPaid.add(request.getAmount());
        
        if (totalPaid.compareTo(invoice.getTotalAmount()) >= 0) {
            // 8. Transition invoice to PAID
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setPaidAt(Instant.now());
            invoiceService.save(invoice);
            
            // 9. Publish event (triggers notification)
            outboxEventPublisher.publish(
                new BusinessInvoicePaidEvent(invoice));
        }
        
        return toResponse(payment);
    }
    
    public List<BusinessPaymentResponse> listByInvoice(Long invoiceId) {
        // Validate invoice exists
        invoiceService.getEntityById(invoiceId);
        
        return paymentRepository.findByInvoiceId(invoiceId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }
    
    public BigDecimal getOutstandingBalance(Long invoiceId) {
        BusinessInvoice invoice = invoiceService.getEntityById(invoiceId);
        
        BigDecimal totalPaid = paymentRepository
            .findByInvoiceId(invoiceId)
            .stream()
            .map(BusinessPayment::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return invoice.getTotalAmount().subtract(totalPaid);
    }
    
    private BusinessPaymentResponse toResponse(BusinessPayment payment) {
        return BusinessPaymentResponse.builder()
            .id(payment.getId())
            .invoiceId(payment.getInvoiceId())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .method(payment.getMethod())
            .notes(payment.getNotes())
            .paidAt(payment.getPaidAt())
            .razorpayPaymentLinkId(payment.getRazorpayPaymentLinkId())
            .createdAt(payment.getCreatedAt())
            .build();
    }
}
```

---

## Payment Methods

### CASH

**Use case:** On-site, field collection, walk-in payment

```json
{
  "amount": 5000.00,
  "method": "CASH",
  "notes": "Collected by Raj at site visit",
  "paidAt": "2026-05-14T14:00:00Z"
}
```

### BANK_TRANSFER

**Use case:** Digital payment, B2B transfers

```json
{
  "amount": 32450.00,
  "method": "BANK_TRANSFER",
  "notes": "UTR 123456789 | Acme Corp Pvt Ltd | AXIS Bank",
  "paidAt": "2026-05-13T09:30:00Z"
}
```

**Verification:**
- UTR (Unique Transaction Reference) uniquely identifies bank transaction
- Record payment once UTR confirmed
- Notes field stores reference info for dispute resolution

### CHEQUE

**Use case:** Traditional payment method, B2B

```json
{
  "amount": 32450.00,
  "method": "CHEQUE",
  "notes": "Cheque #42 | HDFC Bank | Dated 2026-05-15",
  "paidAt": "2026-05-14T16:00:00Z"
}
```

**Workflow:**
1. Cheque received
2. Record payment with date received
3. Bank deposit (update `paidAt` if needed for accounting)
4. Clear reconciliation

### RAZORPAY_LINK

**Use case:** Online payment, hosted checkout

```json
{
  "amount": 32450.00,
  "method": "RAZORPAY_LINK",
  "paidAt": "2026-05-14T15:45:00Z"
}
```

**Automatic:**
- Payment link created via API
- Customer pays through Razorpay
- Webhook automatically records payment
- No manual `POST /api/v1/business-payments` needed

---

## Reconciliation Workflow

### Workflow: Monthly Invoice Reconciliation

```
1. Generate invoice
   POST /api/v1/business-invoices
   → Invoice created (DRAFT)

2. Finalize invoice
   POST /api/v1/business-invoices/{id}/finalize
   → Invoice is now OPEN

3. Send to customer
   POST /api/v1/business-invoices/{id}/send
   → Email sent with payment link

4. Monitor payments
   GET /api/v1/business-payments/invoice/{id}/outstanding
   → Check outstanding balance

5. Record incoming payments
   Loop:
     5a. Receive payment (bank transfer, cheque, cash)
     5b. POST /api/v1/business-payments/{id}
     5c. Records payment and tracks balance

6. Verify completion
   GET /api/v1/business-payments/invoice/{id}/outstanding
   → Returns 0 when fully paid
   
7. Invoice auto-transitions to PAID
   GET /api/v1/business-invoices/{id}
   → status = PAID, paidAt = <timestamp>
```

---

## Error Handling

See [error-handling.md](./error-handling.md#business-error-codes) for complete error list.

**Common errors:**

| Error Code | HTTP | Message | Cause |
|------------|------|---------|-------|
| `BIZ_PAY_001` | 400 | Payment exceeds outstanding balance | amount > (total - collected) |
| `BIZ_PAY_002` | 400 | Invoice not OPEN | Can only record for OPEN invoices |
| `BIZ_PAY_003` | 400 | Invalid payment method | method not in enum |
| `BIZ_PAY_004` | 400 | Amount must be greater than zero | amount ≤ 0 |
| `BIZ_PAY_005` | 404 | Invoice not found | invoiceId doesn't exist |

---

## State Transitions

### Payment Recording Flow

```
Invoice: OPEN
  │
  ├─ Record Payment 1 (₹32,450)
  │   Outstanding: ₹64,900 - ₹32,450 = ₹32,450
  │
  ├─ Record Payment 2 (₹32,450)
  │   Outstanding: ₹64,900 - ₹64,900 = ₹0
  │   → Invoice transitions to PAID
  │
  └─ Invoice: PAID ✓
```

---

## Reporting & Analytics

### Payment Dashboard Queries

```sql
-- Total collected vs due
SELECT
  i.id,
  i.invoice_number,
  i.total_amount,
  COALESCE(SUM(p.amount), 0) as collected,
  i.total_amount - COALESCE(SUM(p.amount), 0) as outstanding,
  i.status
FROM business_invoices i
LEFT JOIN business_payments p ON i.id = p.invoice_id
WHERE i.customer_id = ?
GROUP BY i.id
ORDER BY i.created_at DESC;

-- Revenue by payment method
SELECT
  p.method,
  COUNT(*) as count,
  SUM(p.amount) as total
FROM business_payments p
WHERE p.created_at >= ?
GROUP BY p.method
ORDER BY total DESC;

-- Days sales outstanding (DSO)
SELECT
  AVG((EXTRACT(EPOCH FROM i.paid_at) - 
       EXTRACT(EPOCH FROM i.created_at)) / 86400) as avg_days_to_collect
FROM business_invoices i
WHERE i.status = 'PAID'
  AND i.paid_at >= ?;
```

---

## Summary

| Feature | Mechanism | Example |
|---------|-----------|---------|
| Partial payments | Multiple POST calls | ₹32,450 + ₹32,450 = ₹64,900 |
| Backdating | `paidAt` in past | Record offline payment from yesterday |
| Balance tracking | Outstanding calculation | ₹64,900 - ₹32,450 = ₹32,450 |
| Auto-transition | Status change on full payment | sum(payments) ≥ total → PAID |
| Multiple methods | Payment method enum | CASH, BANK_TRANSFER, CHEQUE, RAZORPAY_LINK |
| Reconciliation | Payment history | Timeline view of all collections |

