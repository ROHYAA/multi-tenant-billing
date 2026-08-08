# Customer Management Module

## Overview

The **Customer Management** module handles the lifecycle of B2B customers within a tenant's ecosystem. It enables businesses to create, manage, and track customer information with tax compliance features (GST in India).

**Module Location:** `src/main/java/com/mtbs/business/customer/`

---

## Customer Lifecycle

```
┌──────────────┐
│   CREATE     │  ✓ New customer registered
│ (Razorpay)   │  ✓ Synced to Razorpay (best-effort)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   ACTIVE     │  ✓ Ready for invoices & payments
│  (Default)   │  ✓ Can be updated
└──────┬───────┘
       │
       ▼ (Optional)
┌──────────────┐
│   UPDATE     │  ✓ Email, phone, address, tax info
│              │  ✓ Name uniqueness per tenant
└──────┬───────┘
       │
       ▼ (Conditional)
┌──────────────┐
│   DELETE     │  ✓ Only if NO active (non-void) invoices
└──────────────┘
```

---

## Data Model

### Customer Entity

```java
@Entity
@Table(name = "customers", schema = "tenant_*")
public class Customer {
    
    // Identity
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Basic Information
    @Column(nullable = false, length = 255)
    private String name;                    // e.g., "Acme Corp"
    
    @Column(nullable = false, unique = true, length = 255)
    private String email;                   // Unique per tenant
    
    @Column(length = 20)
    private String phone;                   // e.g., "+91-9876543210"
    
    @Column(columnDefinition = "TEXT")
    private String address;                 // Full address
    
    // Tax Compliance
    @Column(length = 15)
    private String gstin;                   // GTIN (15-char), GST identification number
    
    // Integration
    @Column(length = 255)
    private String razorpayCustomerId;      // Synced customer ID
    
    // Audit
    @CreationTimestamp
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

### Key Fields Explained

| Field | Type | Constraints | Notes |
|-------|------|-----------|-------|
| `name` | String | Required, length ≤ 255 | Business/entity name |
| `email` | String | Required, unique/tenant | Primary contact email |
| `phone` | String | Optional, length ≤ 20 | International format supported |
| `address` | String | Optional, text | Full address (street, city, state, PIN) |
| `gstin` | String | Optional, length = 15 | GST Identification Number (India compliance) |
| `razorpayCustomerId` | String | Optional | External payment provider reference |

---

## API Endpoints

### Create Customer

```http
POST /api/v*/customers

Content-Type: application/json

{
  "name": "Acme Corporation",
  "email": "billing@acme.com",
  "phone": "+91-9876543210",
  "address": "123 Tech Park, Bangalore 560001, India",
  "gstin": "27AABCT1234H1Z0"
}
```

**Response: 201 Created**
```json
{
  "id": 1,
  "name": "Acme Corporation",
  "email": "billing@acme.com",
  "phone": "+91-9876543210",
  "address": "123 Tech Park, Bangalore 560001, India",
  "gstin": "27AABCT1234H1Z0",
  "razorpayCustomerId": "cust_A1b2C3d4E5f6G7h8i",
  "createdAt": "2026-05-15T10:30:00Z",
  "updatedAt": "2026-05-15T10:30:00Z"
}
```

**Errors:**
- `409 CONFLICT` — Email already exists for this tenant
- `400 BAD_REQUEST` — Invalid GSTIN format (must be 15 alphanumeric)

---

### List Customers

```http
GET /api/v*/customers?search=acme&page=0&size=20&sort=name,asc

Authorization: Bearer <token>
```

**Response: 200 OK**
```json
{
  "content": [
    {
      "id": 1,
      "name": "Acme Corporation",
      "email": "billing@acme.com",
      "phone": "+91-9876543210",
      "gstin": "27AABCT1234H1Z0",
      "createdAt": "2026-05-15T10:30:00Z"
    },
    {
      "id": 2,
      "name": "Acme North",
      "email": "accounts@acme-north.com",
      "phone": "+91-9876543211",
      "gstin": "27AABCU5678H2Z1",
      "createdAt": "2026-05-16T14:15:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1
}
```

**Query Parameters:**
- `search` — Search by name or email (case-insensitive substring match)
- `page` — Page number (0-indexed, default 0)
- `size` — Page size (default 20)
- `sort` — Sort order (e.g., `name,asc` or `createdAt,desc`)

---

### Get Customer

```http
GET /api/v*/customers/1

Authorization: Bearer <token>
```

**Response: 200 OK**
```json
{
  "id": 1,
  "name": "Acme Corporation",
  "email": "billing@acme.com",
  "phone": "+91-9876543210",
  "address": "123 Tech Park, Bangalore 560001, India",
  "gstin": "27AABCT1234H1Z0",
  "razorpayCustomerId": "cust_A1b2C3d4E5f6G7h8i",
  "createdAt": "2026-05-15T10:30:00Z",
  "updatedAt": "2026-05-15T10:30:00Z"
}
```

**Errors:**
- `404 NOT_FOUND` — Customer does not exist or belongs to different tenant
- `401 UNAUTHORIZED` — Invalid/expired token

---

### Update Customer

```http
PUT /api/v*/customers/1

Content-Type: application/json

{
  "name": "Acme Corporation Ltd.",
  "phone": "+91-9876543210",
  "address": "123 Tech Park, Bangalore 560001, India",
  "gstin": "27AABCT1234H1Z0"
}
```

**Response: 200 OK**
```json
{
  "id": 1,
  "name": "Acme Corporation Ltd.",
  "email": "billing@acme.com",
  "phone": "+91-9876543210",
  "address": "123 Tech Park, Bangalore 560001, India",
  "gstin": "27AABCT1234H1Z0",
  "razorpayCustomerId": "cust_A1b2C3d4E5f6G7h8i",
  "createdAt": "2026-05-15T10:30:00Z",
  "updatedAt": "2026-05-26T15:45:00Z"
}
```

**Validation:**
- Email is NOT changeable (used as unique identifier)
- GSTIN must be valid 15-character alphanumeric if provided
- Name uniqueness enforced per tenant

**Errors:**
- `404 NOT_FOUND` — Customer does not exist
- `400 BAD_REQUEST` — Invalid GSTIN format

---

### Delete Customer

```http
DELETE /api/v*/customers/1

Authorization: Bearer <token>
```

**Response: 204 No Content**

**Constraints:**
- ✅ Can delete if NO invoices (DRAFT or OPEN) exist for this customer
- ❌ Cannot delete if active invoices reference this customer
- ℹ️ VOID/PAID invoices do NOT prevent deletion (historical records)

**Errors:**
- `404 NOT_FOUND` — Customer does not exist
- `409 CONFLICT` — Customer has active invoices; cannot delete
  ```json
  {
    "errorCode": "RESOURCE_CONFLICT",
    "message": "Customer has 3 active invoices. Please void or finalize them before deletion.",
    "detail": "Active invoices: BINV-12345-202605-001, BINV-12345-202605-002, ..."
  }
  ```

---

## Service Implementation

### CustomerService

```java
@Service
@RequiredArgsConstructor
public class CustomerService {
    
    private final CustomerRepository customerRepository;
    private final RazorpayPort razorpayPort;
    private final BusinessInvoiceRepository invoiceRepository;
    
    /**
     * Create new customer for current tenant.
     * Syncs to Razorpay (best-effort; failures logged but not blocking).
     */
    public Customer create(CreateCustomerRequest request) {
        // Validate email uniqueness per tenant
        if (customerRepository.existsByEmail(request.email())) {
            throw ResourceException.alreadyExists("Customer with email already exists");
        }
        
        // Create customer entity
        Customer customer = new Customer();
        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setAddress(request.address());
        customer.setGstin(request.gstin());
        
        // Save to DB first
        Customer saved = customerRepository.save(customer);
        
        // Sync to Razorpay (async, best-effort)
        try {
            String razorpayId = razorpayPort.createCustomer(
                CreateRazorpayCustomerRequest.builder()
                    .email(saved.getEmail())
                    .name(saved.getName())
                    .phone(saved.getPhone())
                    .build()
            );
            saved.setRazorpayCustomerId(razorpayId);
            customerRepository.save(saved);
        } catch (Exception e) {
            // Log but don't fail; customer created locally regardless
            log.warn("Failed to sync customer {} to Razorpay: {}", 
                saved.getId(), e.getMessage());
        }
        
        return saved;
    }
    
    /**
     * Retrieve customer by ID (tenant-scoped).
     */
    public Customer getById(Long customerId) {
        return customerRepository.findById(customerId)
            .orElseThrow(() -> ResourceException.notFound("Customer not found"));
    }
    
    /**
     * List customers with optional search filter (paginated).
     */
    public Page<CustomerDTO> list(String search, Pageable pageable) {
        if (search != null && !search.isEmpty()) {
            String pattern = "%" + search.toLowerCase() + "%";
            return customerRepository.findByNameIgnoreCaseContainingOrEmailIgnoreCaseContaining(
                pattern, pattern, pageable
            ).map(CustomerMapper::toDTO);
        }
        return customerRepository.findAll(pageable).map(CustomerMapper::toDTO);
    }
    
    /**
     * Update customer information.
     */
    public Customer update(Long customerId, UpdateCustomerRequest request) {
        Customer customer = getById(customerId);
        
        // Update fields
        if (request.name() != null) customer.setName(request.name());
        if (request.phone() != null) customer.setPhone(request.phone());
        if (request.address() != null) customer.setAddress(request.address());
        if (request.gstin() != null) customer.setGstin(request.gstin());
        
        return customerRepository.save(customer);
    }
    
    /**
     * Delete customer only if NO active invoices exist.
     */
    public void delete(Long customerId) {
        Customer customer = getById(customerId);
        
        // Check for active invoices (DRAFT or OPEN status)
        long activeInvoiceCount = invoiceRepository.countByCustomerIdAndStatusIn(
            customerId, 
            List.of(InvoiceStatus.DRAFT, InvoiceStatus.OPEN)
        );
        
        if (activeInvoiceCount > 0) {
            throw ResourceException.conflict(
                "Customer has " + activeInvoiceCount + " active invoices"
            );
        }
        
        customerRepository.deleteById(customerId);
    }
}
```

---

## Permission & Authorization

**Required Permission:** `PERMISSION_CUSTOMER_MANAGE`

- All customer endpoints require this permission
- Enforced at controller level via `@Secured` annotation
- Tenant-scoped automatically via TenantContext

```java
@RestController
@RequestMapping("/api/v*/customers")
@RequiredArgsConstructor
public class CustomerController {
    
    @PostMapping
    @Secured("PERMISSION_CUSTOMER_MANAGE")
    public ResponseEntity<CustomerDTO> create(@RequestBody CreateCustomerRequest request) {
        // ...
    }
    
    @GetMapping
    @Secured("PERMISSION_CUSTOMER_MANAGE")
    public ResponseEntity<Page<CustomerDTO>> list(...) {
        // ...
    }
}
```

---

## Razorpay Synchronization

### Best-Effort Design

Customer creation in MTBS **synchronizes to Razorpay**, but failures do not block the operation:

1. **Create** in MTBS database ✅ (primary operation)
2. **Sync** to Razorpay 🔄 (async, best-effort)
   - Success: `razorpayCustomerId` stored in database
   - Failure: Logged but customer remains usable locally
   - Retry: Manual sync via admin API (future feature)

### Benefits

- **Resilience:** Razorpay outage doesn't block customer creation
- **Flexibility:** Customers can be created and used locally first
- **Eventual Consistency:** Sync happens asynchronously

### Implementation

```java
try {
    String razorpayId = razorpayPort.createCustomer(/*...*/);
    customer.setRazorpayCustomerId(razorpayId);
    customerRepository.save(customer);
    log.info("Customer {} synced to Razorpay: {}", customer.getId(), razorpayId);
} catch (Exception e) {
    log.warn("Failed to sync customer {} to Razorpay: {}", 
        customer.getId(), e.getMessage(), e);
    // Continue; don't fail
}
```

---

## Tax Compliance

### GSTIN (GST Identification Number) — India

**Field:** `gstin` (optional, length = 15)

**Format:** 15-character alphanumeric
- Example: `27AABCT1234H1Z0`
- Breakdown:
  - `27` — State code (Karnatka)
  - `AABCT` — PAN (5 chars)
  - `1234H1` — Registration number (6 chars)
  - `Z0` — Checksum (2 chars)

**Validation Rules:**
- Length must be exactly 15 characters
- Alphanumeric only (A-Z, 0-9, no spaces)
- No validation of checksum (external GSTN verification recommended)

**Usage:**
- Printed on invoices (compliance requirement)
- Used for tax report generation
- Stored for audit trail

---

## Deletion Constraints

### Why Cannot Delete with Active Invoices?

**Historical Integrity:**
- Business invoices reference `customerId` as foreign key
- Deleting customer would break invoice history and tax records
- Violates data integrity and compliance requirements

### Deletion Logic

```
Can Delete If:
├─ NO DRAFT invoices (not finalized)
├─ NO OPEN invoices (not yet paid)
│
Cannot Delete If:
├─ ANY DRAFT invoices exist
├─ ANY OPEN invoices exist
│
Note: PAID and VOID invoices do NOT prevent deletion
```

**Recommendation:**
If customer is permanently inactive, create a "Deactivated" status (future enhancement) instead of deletion.

---

## Best Practices

### 1. Email as Unique Identifier

- Email is **immutable** per customer (cannot be updated)
- Enables safe customer lookups and reconciliation
- Recommended: validate email format before creation

### 2. GSTIN Validation

- Always collect GSTIN from B2B customers in India
- Validate format (15 alphanumeric) server-side
- Consider external GSTN API validation for compliance

### 3. Deletion Strategy

- **Never delete customers.** Instead:
  - Keep customer record with `status` field (future)
  - Soft-delete with `deletedAt` timestamp
  - Archive to separate table

### 4. Razorpay Sync

- Expect Razorpay sync failures in production
- Log all sync failures for monitoring
- Provide manual re-sync endpoint (future)

---

## Integration Points

### 1. With Invoicing

- Customers linked via 1:N relationship to BusinessInvoice
- InvoiceService validates customer exists before invoice creation

### 2. With Payments

- Payment amounts collected against invoices, not customers
- Customer data used for invoice generation only

### 3. With Reporting

- Revenue reports grouped by customer (future enhancement)
- Customer lifetime value (CLV) calculation (future)

---

## Future Enhancements

🔜 **Customer Status Field** — ACTIVE, INACTIVE, SUSPENDED  
🔜 **Soft Deletion** — Archive old customers with `deletedAt` timestamp  
🔜 **Customer Segmentation** — Tags, tiers, lifetime value  
🔜 **Billing Address vs Shipping Address** — Separate entities  
🔜 **Credit Limits** — Prevent invoicing beyond threshold  
🔜 **Auto-Retry on Razorpay Sync** — Background job for failed syncs  
🔜 **Customer Analytics** — LTV, churn, payment history dashboard  
🔜 **Bulk Customer Import** — CSV upload functionality  

---

## Related Documentation

- [Invoicing Module - Business Invoices](./invoicing.md)
- [Payment Recording](./payments.md)
- [Reporting & Analytics](./reporting.md)
- [Multi-Tenancy Isolation](../backend/02-multi-tenancy/isolation.md)
