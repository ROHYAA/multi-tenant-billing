---
version: 1.0
date: 2026-05-14
author: Manoj Pandi
status: Production Ready
tags:
  - api
  - business-api
  - customers
  - products
  - catalog
related_documents:
  - ./business-invoices.md
  - ./business-payments.md
  - ../04-business-modules/invoicing.md
  - ../07-data-model/entities.md
  - ./error-handling.md
---

# Business API (Customers & Products)

## Executive Summary

The Business API manages two core entities for invoicing workflows: **Customers** (people/businesses you bill) and **Products** (catalog of items you sell). Both support CRUD operations with soft deletion, search filtering, and integration with Razorpay (customers) and invoice creation (products).

---

## Context & Problem

### Business Entity Hierarchy

```
Tenant
  ├─ Customer 1
  │   ├─ Invoice 1 (BINV-456-202605-0001)
  │   │   ├─ Line Item → Product A
  │   │   └─ Line Item → Product B
  │   │
  │   ├─ Invoice 2 (BINV-456-202605-0002)
  │   │   └─ Line Item → Product A
  │   │
  │   └─ Payment 1 (UTR 123456789)
  │
  ├─ Customer 2
  │   └─ Invoice 3
  │
  ├─ Product A (name: "Professional Plan", price: ₹5,000)
  ├─ Product B (name: "Training", price: ₹2,000)
  └─ Product C (name: "License", price: ₹10,000)
```

### Why Both Exist

| Entity | Purpose | Example |
|--------|---------|---------|
| **Customer** | Who to bill | Acme Corp, John Doe |
| **Product** | What to bill for | "Professional Plan", "Training Hours", "License" |

**Billing workflow:**
1. Create customer
2. Create product(s)
3. Create invoice → select customer + add line items (products)
4. Record payment(s)

---

## Dependencies

### Inbound

- **Frontend (React)** — Customer/product management dashboard
- **Invoice API** — References customers and products

### Outbound

- `CustomerService` — Persistence, Razorpay sync
- `ProductService` — Catalog management
- `Razorpay API` — Sync customer for payment links
- `Database` — PostgreSQL (tenant schema)

### Configuration

```yaml
business:
  customer:
    allow-duplicate-emails: false
    sync-to-razorpay: true
    
  product:
    allow-duplicate-names: false
    default-tax-percentage: 0
    allow-deactivation: true
```

---

## Customers Endpoints

### POST /api/v1/customers

**Purpose:** Create a new customer

**Request:**
```json
{
  "name": "Acme Corp",
  "email": "billing@acme.com",
  "phone": "+91-9876543210",
  "address": "123 Business Street, Mumbai, India",
  "gstin": "27AAPCS1234H1Z0"
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": 42,
    "name": "Acme Corp",
    "email": "billing@acme.com",
    "phone": "+91-9876543210",
    "address": "123 Business Street, Mumbai, India",
    "gstin": "27AAPCS1234H1Z0",
    "razorpayCustomerId": "cust_2c7P8k4x9M3z5q",
    "createdAt": "2026-05-14T14:30:00Z",
    "updatedAt": "2026-05-14T14:30:00Z"
  },
  "message": "Customer created successfully"
}
```

**Validations:**
- `name` — Required, max 255 chars
- `email` — Valid email format, unique per tenant
- `phone` — Optional, max 50 chars
- `address` — Optional, can be long text
- `gstin` — Optional, 15-char alphanumeric (GST registration for B2B)

**Side effects:**
- Stores customer in tenant schema
- Best-effort sync to Razorpay (for payment links later)
- Records in `AuditLog`

**Error codes:**
- `400` — Email already exists in this tenant
- `400` — Invalid GSTIN format
- `400` — Invalid email format

---

### GET /api/v1/customers

**Purpose:** List all customers

**Request:**
```
GET /api/v1/customers?search=Acme&page=0&size=20&sort=createdAt,desc
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 42,
        "name": "Acme Corp",
        "email": "billing@acme.com",
        "phone": "+91-9876543210",
        "address": "123 Business Street, Mumbai, India",
        "gstin": "27AAPCS1234H1Z0",
        "razorpayCustomerId": "cust_2c7P8k4x9M3z5q",
        "createdAt": "2026-05-14T14:30:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "currentPage": 0,
    "pageSize": 20
  },
  "message": "Customers fetched successfully"
}
```

**Parameters:**
- `search` — Optional, matches name or email (case-insensitive, substring)
- `page`, `size` — Pagination
- Default sort: `createdAt` DESC

**Search example:**
- Query: "acme" → matches "Acme Corp", "ACME Industries", etc.
- Query: "billing" → matches "billing@acme.com", "billing@company.com"

---

### GET /api/v1/customers/{id}

**Purpose:** Get single customer

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 42,
    "name": "Acme Corp",
    "email": "billing@acme.com",
    "phone": "+91-9876543210",
    "address": "123 Business Street, Mumbai, India",
    "gstin": "27AAPCS1234H1Z0",
    "razorpayCustomerId": "cust_2c7P8k4x9M3z5q",
    "createdAt": "2026-05-14T14:30:00Z",
    "updatedAt": "2026-05-14T14:30:00Z"
  },
  "message": "Customer fetched successfully"
}
```

---

### PUT /api/v1/customers/{id}

**Purpose:** Update customer

**Request:** (all fields optional, send only what changed)
```json
{
  "name": "Acme Corporation",
  "phone": "+91-9876543211",
  "gstin": "27AAPCS1234H1Z1"
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 42,
    "name": "Acme Corporation",
    "email": "billing@acme.com",
    "phone": "+91-9876543211",
    "address": "123 Business Street, Mumbai, India",
    "gstin": "27AAPCS1234H1Z1",
    "razorpayCustomerId": "cust_2c7P8k4x9M3z5q",
    "updatedAt": "2026-05-14T15:45:00Z"
  },
  "message": "Customer updated successfully"
}
```

**Error codes:**
- `400` — Email already taken (by another customer)
- `400` — Invalid GSTIN format
- `404` — Customer not found

---

### DELETE /api/v1/customers/{id}

**Purpose:** Soft-delete a customer

**Response (200 OK):**
```json
{
  "success": true,
  "data": null,
  "message": "Customer deleted successfully"
}
```

**Preconditions:**
- No open or paid invoices (must void first)

**Error codes:**
- `400` — Customer has active invoices (OPEN or PAID)
- `404` — Customer not found

**Side effects:**
- Marks customer as deleted (soft delete)
- Historical invoices remain visible for audit
- Cannot be undone

---

## Products Endpoints

### POST /api/v1/products

**Purpose:** Add a product to catalog

**Request:**
```json
{
  "name": "Professional Plan",
  "description": "Annual professional tier with unlimited users",
  "price": 5000.00,
  "taxPercentage": 18.00,
  "hsnSacCode": "998361",
  "unit": "subscription"
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Professional Plan",
    "description": "Annual professional tier with unlimited users",
    "price": 5000.00,
    "taxPercentage": 18.00,
    "hsnSacCode": "998361",
    "unit": "subscription",
    "isActive": true,
    "createdAt": "2026-05-14T14:30:00Z",
    "updatedAt": "2026-05-14T14:30:00Z"
  },
  "message": "Product created successfully"
}
```

**Validations:**
- `name` — Required, unique per tenant, max 255 chars
- `description` — Optional
- `price` — Required, ≥ 0
- `taxPercentage` — Optional, default 0 (tax-exempt)
- `hsnSacCode` — Optional, GST classification (max 20 chars)
- `unit` — Optional, e.g. "hours", "kg", "license" (max 50 chars)

**Tax calculation:**
```
If taxPercentage = 18 and price = 5000:
  Tax amount per unit = 5000 × 18 / 100 = 900
  Total per unit = 5000 + 900 = 5900
```

**Error codes:**
- `400` — Product name already exists
- `400` — Invalid price (negative)

---

### GET /api/v1/products

**Purpose:** List all products (active and inactive)

**Request:**
```
GET /api/v1/products?search=Professional&page=0&size=20
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "name": "Professional Plan",
        "description": "Annual professional tier",
        "price": 5000.00,
        "taxPercentage": 18.00,
        "hsnSacCode": "998361",
        "unit": "subscription",
        "isActive": true,
        "createdAt": "2026-05-14T14:30:00Z"
      },
      {
        "id": 2,
        "name": "Professional Plan (Legacy)",
        "price": 4000.00,
        "isActive": false,
        "createdAt": "2026-04-01T00:00:00Z"
      }
    ],
    "totalElements": 2,
    "totalPages": 1,
    "currentPage": 0,
    "pageSize": 20
  },
  "message": "Products fetched successfully"
}
```

**Parameters:**
- `search` — Optional, matches product name
- Ordering: Active first, then alphabetically by name

---

### GET /api/v1/products/active

**Purpose:** Get active products only (for invoice creation)

**Request:**
```
GET /api/v1/products/active
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Professional Plan",
      "price": 5000.00,
      "taxPercentage": 18.00,
      "unit": "subscription",
      "isActive": true
    },
    {
      "id": 3,
      "name": "Training Hours",
      "price": 2000.00,
      "taxPercentage": 18.00,
      "unit": "hours",
      "isActive": true
    }
  ],
  "message": "Active products fetched successfully"
}
```

**Use case:** Populate dropdown when creating invoice line items

**Features:**
- Not paginated (returns all active products)
- Alphabetically sorted

---

### GET /api/v1/products/{id}

**Purpose:** Get single product (active or inactive)

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Professional Plan",
    "description": "Annual professional tier",
    "price": 5000.00,
    "taxPercentage": 18.00,
    "hsnSacCode": "998361",
    "unit": "subscription",
    "isActive": true,
    "createdAt": "2026-05-14T14:30:00Z",
    "updatedAt": "2026-05-14T14:30:00Z"
  },
  "message": "Product fetched successfully"
}
```

---

### PUT /api/v1/products/{id}

**Purpose:** Update product

**Request:** (all optional)
```json
{
  "price": 6000.00,
  "description": "Updated description",
  "taxPercentage": 18.00
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Professional Plan",
    "price": 6000.00,
    "taxPercentage": 18.00,
    "isActive": true,
    "updatedAt": "2026-05-14T15:45:00Z"
  },
  "message": "Product updated successfully"
}
```

**Critical:**
- Price/tax changes **DO NOT affect** existing invoices
- Values are **snapshotted** at invoice creation time
- Only applies to future invoices

**Error codes:**
- `400` — Product name already taken
- `404` — Product not found

---

### DELETE /api/v1/products/{id}

**Purpose:** Deactivate a product

**Response (200 OK):**
```json
{
  "success": true,
  "data": null,
  "message": "Product deactivated successfully"
}
```

**Effect:**
- Sets `isActive = false`
- Cannot be added to **new** invoices
- Existing invoice line items remain visible (immutable)
- Can be reactivated by updating

**Rationale:** Maintain audit trail of historical pricing

---

## Integration Patterns

### Frontend: Customer/Product Management

```typescript
// Create customer
const useCreateCustomer = () => {
  return useMutation({
    mutationFn: (customer) => 
      apiClient.post('/api/v1/customers', customer),
    onSuccess: (response) => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      showSuccess('Customer created');
    }
  });
};

// Search customers for dropdown
const useSearchCustomers = (search) => {
  return useQuery({
    queryKey: ['customers', search],
    queryFn: () => apiClient.get('/api/v1/customers', {
      params: { search, size: 10 }
    }),
    enabled: search.length > 0
  });
};

// Create product
const useCreateProduct = () => {
  return useMutation({
    mutationFn: (product) =>
      apiClient.post('/api/v1/products', product),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      showSuccess('Product added');
    }
  });
};

// Get active products for invoice line item dropdown
const useActiveProducts = () => {
  return useQuery({
    queryKey: ['activeProducts'],
    queryFn: () => apiClient.get('/api/v1/products/active')
  });
};
```

### Customer Form Component

```typescript
function CustomerForm() {
  const [form, setForm] = useState({
    name: '',
    email: '',
    phone: '',
    address: '',
    gstin: ''
  });

  const { mutate: createCustomer, isPending } = useCreateCustomer();

  const handleSubmit = (e) => {
    e.preventDefault();
    createCustomer(form);
  };

  return (
    <form onSubmit={handleSubmit}>
      <input
        placeholder="Customer name"
        value={form.name}
        onChange={(e) => setForm({ ...form, name: e.target.value })}
        required
      />

      <input
        type="email"
        placeholder="Email"
        value={form.email}
        onChange={(e) => setForm({ ...form, email: e.target.value })}
        required
      />

      <input
        placeholder="Phone"
        value={form.phone}
        onChange={(e) => setForm({ ...form, phone: e.target.value })}
      />

      <textarea
        placeholder="Address"
        value={form.address}
        onChange={(e) => setForm({ ...form, address: e.target.value })}
      />

      <input
        placeholder="GSTIN (15-char)"
        value={form.gstin}
        onChange={(e) => setForm({ ...form, gstin: e.target.value })}
        pattern="[0-9A-Z]{15}"
      />

      <button type="submit" disabled={isPending}>
        Create Customer
      </button>
    </form>
  );
}
```

### Invoice Creation with Product Selection

```typescript
function InvoiceCreator() {
  const { data: customers } = useQuery({
    queryKey: ['customers'],
    queryFn: () => apiClient.get('/api/v1/customers')
  });

  const { data: activeProducts } = useActiveProducts();

  const [form, setForm] = useState({
    customerId: '',
    items: [{ productId: null, quantity: 1 }]
  });

  const handleAddItem = () => {
    setForm({
      ...form,
      items: [...form.items, { productId: null, quantity: 1 }]
    });
  };

  const handleSubmit = () => {
    apiClient.post('/api/v1/business-invoices', {
      customerId: form.customerId,
      items: form.items.map(item => ({
        productId: item.productId,
        quantity: item.quantity
      }))
    });
  };

  return (
    <div>
      <select
        value={form.customerId}
        onChange={(e) => setForm({ ...form, customerId: e.target.value })}
      >
        <option value="">Select customer</option>
        {customers?.data?.content?.map(c => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>

      {form.items.map((item, idx) => (
        <div key={idx}>
          <select
            value={item.productId || ''}
            onChange={(e) => {
              const newItems = [...form.items];
              newItems[idx].productId = parseInt(e.target.value);
              setForm({ ...form, items: newItems });
            }}
          >
            <option value="">Select product</option>
            {activeProducts?.data?.map(p => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>

          <input
            type="number"
            value={item.quantity}
            onChange={(e) => {
              const newItems = [...form.items];
              newItems[idx].quantity = parseInt(e.target.value);
              setForm({ ...form, items: newItems });
            }}
          />
        </div>
      ))}

      <button onClick={handleAddItem}>Add Item</button>
      <button onClick={handleSubmit}>Create Invoice</button>
    </div>
  );
}
```

### Backend: Service Implementation

```java
@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {
    
    private final CustomerRepository repository;
    private final PaymentGatewayPort paymentGateway;
    
    public CustomerResponse create(CreateCustomerRequest request) {
        // Validate email uniqueness
        if (repository.existsByEmail(request.getEmail())) {
            throw BillingException.duplicateEmail();
        }
        
        // Create customer
        Customer customer = Customer.builder()
            .name(request.getName())
            .email(request.getEmail())
            .phone(request.getPhone())
            .address(request.getAddress())
            .gstin(request.getGstin())
            .build();
        
        repository.save(customer);
        
        // Sync to Razorpay (best-effort)
        try {
            String razorpayId = paymentGateway.syncCustomer(customer);
            customer.setRazorpayCustomerId(razorpayId);
            repository.save(customer);
        } catch (Exception e) {
            log.warn("Failed to sync customer to Razorpay", e);
            // Continue anyway — Razorpay sync is optional
        }
        
        return toResponse(customer);
    }
}

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {
    
    private final ProductRepository repository;
    
    public ProductResponse create(CreateProductRequest request) {
        // Validate name uniqueness
        if (repository.existsByName(request.getName())) {
            throw BillingException.duplicateProductName();
        }
        
        // Create product
        Product product = Product.builder()
            .name(request.getName())
            .description(request.getDescription())
            .price(request.getPrice())
            .taxPercentage(request.getTaxPercentage() != null
                ? request.getTaxPercentage()
                : BigDecimal.ZERO)
            .hsnSacCode(request.getHsnSacCode())
            .unit(request.getUnit())
            .isActive(true)
            .build();
        
        repository.save(product);
        
        return toResponse(product);
    }
}
```

---

## Error Handling

See [error-handling.md](./error-handling.md#business-error-codes) for complete error list.

**Common errors:**

| Error Code | HTTP | Message | Cause |
|------------|------|---------|-------|
| `BIZ_CUST_001` | 400 | Email already exists | Duplicate email in tenant |
| `BIZ_CUST_002` | 400 | Cannot delete customer with invoices | Has OPEN or PAID invoices |
| `BIZ_PROD_001` | 400 | Product name already exists | Duplicate name in tenant |
| `BIZ_PROD_002` | 400 | Invalid tax percentage | Negative value |

---

## Best Practices

### Customers

- **Keep GSTIN updated** — Required for B2B GST invoices
- **Email is unique** — Cannot have duplicate emails per tenant
- **Search before creating** — Check for existing customers with similar names
- **Soft delete only** — Deleted customers still appear in historical invoices

### Products

- **Name uniqueness** — Name must be unique per tenant
- **Plan for tax** — Set correct taxPercentage at creation (common mistake: forgetting GST)
- **HSN/SAC codes** — Required for GST compliance in India (18-digit classification)
- **Units matter** — Use consistent units ("hrs", "kg", "licenses") for clarity
- **Price changes safe** — Updating price does NOT affect historical invoices

### Workflow

```
1. Create Customers
   POST /api/v1/customers

2. Create Products
   POST /api/v1/products

3. Create Invoices (references customers + products)
   POST /api/v1/business-invoices

4. Invoice → Line Items → Products (snapshotted)

5. Update Product price
   PUT /api/v1/products/{id}
   (Only affects new invoices, not existing ones)
```

---

## Summary

| Feature | Mechanism | Example |
|---------|-----------|---------|
| Customer creation | CRUD with Razorpay sync | Create → Razorpay ID stored |
| Product catalog | CRUD with active/inactive | Deactivate old products |
| Email uniqueness | Per-tenant validation | Cannot create duplicate emails |
| Tax calculation | Per-line-item snapshots | 18% GST on product |
| Price immutability | Snapshot at invoice creation | Product price change doesn't affect past invoices |
| Search | Substring matching | "Acme" matches "Acme Corp" |
| Soft delete | Archive, don't destroy | Deleted customers still in audit trail |

