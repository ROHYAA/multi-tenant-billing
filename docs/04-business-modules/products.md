# Product Catalog Module

## Overview

The **Product Catalog** module manages the inventory of products/services offered by a tenant. It enables businesses to define pricing, taxes, units, and maintain a product catalog from which invoice line items are created.

**Module Location:** `src/main/java/com/mtbs/business/product/`

---

## Product Lifecycle

```
┌──────────────────────────┐
│       CREATE             │  ✓ New product added to catalog
│   (ACTIVE by default)    │  ✓ Ready for invoicing
└──────────┬───────────────┘
           │
           ▼
┌──────────────────────────┐
│       ACTIVE             │  ✓ Can be used in new invoices
│      (Default)           │  ✓ Can be updated anytime
└──────────┬───────────────┘
           │ (Optional)
           ▼
┌──────────────────────────┐
│      DEACTIVATE          │  ✓ Blocks from new invoices
│                          │  ✓ Historical items unaffected
└──────────────────────────┘
```

### Design Philosophy

**Products are NEVER deleted**, only deactivated:
- Historical invoice items reference product by ID
- Deactivation prevents future use while preserving audit trail
- Tax/pricing changes affect only new invoices, not historical ones

---

## Data Model

### Product Entity

```java
@Entity
@Table(name = "products", schema = "tenant_*")
public class Product {
    
    // Identity
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Core Product Info
    @Column(nullable = false, length = 255)
    private String name;                    // e.g., "Premium Hosting"
    
    @Column(columnDefinition = "TEXT")
    private String description;             // Detailed product description
    
    @Column(precision = 19, scale = 2)
    private BigDecimal price;               // Default price (snapshotted in invoices)
    
    // Tax Compliance (India GST)
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPercentage;       // e.g., 18.00 for 18% GST
    
    @Column(length = 20)
    private String hsnSacCode;              // HSN (goods) or SAC (services)
                                            // HSN example: "7209.16.00"
                                            // SAC example: "9983"
    
    @Column(length = 50)
    private String unit;                    // Unit of measurement
                                            // e.g., "hrs", "GB", "licenses"
    
    // Status
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ProductStatus status;           // ACTIVE or DEACTIVATED
    
    // Audit
    @CreationTimestamp
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

public enum ProductStatus {
    ACTIVE,              // Available for new invoices
    DEACTIVATED         // Blocked from new invoices
}
```

### Key Fields Explained

| Field | Type | Constraints | Notes |
|-------|------|-----------|-------|
| `name` | String | Required, length ≤ 255 | Display name on invoices |
| `description` | String | Optional | Long-form product description |
| `price` | BigDecimal | Required, precision 19,2 | Default unit price; snapshotted in invoices |
| `taxPercentage` | BigDecimal | Required, precision 5,2 | GST rate (e.g., 18.00, 5.00, 0.00) |
| `hsnSacCode` | String | Optional, length ≤ 20 | Tax classification code (India) |
| `unit` | String | Optional, length ≤ 50 | Unit of measurement (hrs, GB, etc.) |
| `status` | Enum | Required | ACTIVE or DEACTIVATED |

---

## API Endpoints

### Create Product

```http
POST /api/v*/products

Content-Type: application/json

{
  "name": "Premium Hosting - Monthly",
  "description": "High-performance cloud hosting with 24/7 support",
  "price": "99.99",
  "taxPercentage": "18.00",
  "hsnSacCode": "9983",
  "unit": "licenses"
}
```

**Response: 201 Created**
```json
{
  "id": 1,
  "name": "Premium Hosting - Monthly",
  "description": "High-performance cloud hosting with 24/7 support",
  "price": "99.99",
  "taxPercentage": "18.00",
  "hsnSacCode": "9983",
  "unit": "licenses",
  "status": "ACTIVE",
  "createdAt": "2026-05-15T10:30:00Z",
  "updatedAt": "2026-05-15T10:30:00Z"
}
```

**Validation:**
- `name` — Required, max 255 characters
- `price` — Required, must be ≥ 0
- `taxPercentage` — Required (can be 0 for tax-exempt), must be between 0-100
- `hsnSacCode` — Optional but recommended for compliance
- `unit` — Optional but recommended (e.g., "hrs", "GB", "units")

**Errors:**
- `400 BAD_REQUEST` — Invalid price or tax percentage
- `409 CONFLICT` — Product name already exists in this tenant

---

### List Products

```http
GET /api/v*/products?search=hosting&status=ACTIVE&page=0&size=20&sort=name,asc

Authorization: Bearer <token>
```

**Response: 200 OK**
```json
{
  "content": [
    {
      "id": 1,
      "name": "Premium Hosting - Monthly",
      "description": "High-performance cloud hosting with 24/7 support",
      "price": "99.99",
      "taxPercentage": "18.00",
      "hsnSacCode": "9983",
      "unit": "licenses",
      "status": "ACTIVE",
      "createdAt": "2026-05-15T10:30:00Z"
    },
    {
      "id": 2,
      "name": "Support - Per Incident",
      "description": "Professional support incident resolution",
      "price": "49.99",
      "taxPercentage": "5.00",
      "hsnSacCode": "9983",
      "unit": "incidents",
      "status": "ACTIVE",
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
- `search` — Search by product name (case-insensitive substring)
- `status` — Filter by status (ACTIVE, DEACTIVATED, or both)
- `page` — Page number (0-indexed, default 0)
- `size` — Page size (default 20)
- `sort` — Sort order (e.g., `name,asc` or `price,desc`)

---

### List Active Products (For Invoice Creation)

```http
GET /api/v*/products/active

Authorization: Bearer <token>
```

**Response: 200 OK**
```json
{
  "content": [
    {
      "id": 1,
      "name": "Premium Hosting - Monthly",
      "price": "99.99",
      "unit": "licenses"
    },
    {
      "id": 2,
      "name": "Support - Per Incident",
      "price": "49.99",
      "unit": "incidents"
    }
  ]
}
```

**Special Features:**
- Returns only ACTIVE products
- Alphabetically ordered for dropdown/select UI
- Lightweight response (minimal fields)
- Cached endpoint (TTL: 5 minutes)

---

### Get Product

```http
GET /api/v*/products/1

Authorization: Bearer <token>
```

**Response: 200 OK**
```json
{
  "id": 1,
  "name": "Premium Hosting - Monthly",
  "description": "High-performance cloud hosting with 24/7 support",
  "price": "99.99",
  "taxPercentage": "18.00",
  "hsnSacCode": "9983",
  "unit": "licenses",
  "status": "ACTIVE",
  "createdAt": "2026-05-15T10:30:00Z",
  "updatedAt": "2026-05-15T10:30:00Z"
}
```

**Errors:**
- `404 NOT_FOUND` — Product does not exist or belongs to different tenant

---

### Update Product

```http
PUT /api/v*/products/1

Content-Type: application/json

{
  "name": "Premium Hosting - Monthly",
  "description": "High-performance cloud hosting with 24/7 support and SLA",
  "price": "109.99",
  "taxPercentage": "18.00",
  "hsnSacCode": "9983",
  "unit": "licenses"
}
```

**Response: 200 OK**
```json
{
  "id": 1,
  "name": "Premium Hosting - Monthly",
  "description": "High-performance cloud hosting with 24/7 support and SLA",
  "price": "109.99",
  "taxPercentage": "18.00",
  "hsnSacCode": "9983",
  "unit": "licenses",
  "status": "ACTIVE",
  "createdAt": "2026-05-15T10:30:00Z",
  "updatedAt": "2026-05-26T15:45:00Z"
}
```

**Important Notes:**
- Price changes affect **only new invoices** (historical items unaffected)
- Tax changes affect **only new invoices**
- Existing invoice line items retain original snapshot prices
- Status cannot be changed via update (use deactivate endpoint)

**Errors:**
- `404 NOT_FOUND` — Product does not exist
- `400 BAD_REQUEST` — Invalid price or tax percentage

---

### Deactivate Product

```http
POST /api/v*/products/1/deactivate

Authorization: Bearer <token>
```

**Response: 204 No Content**

**Effects:**
- ✅ Product status changed to DEACTIVATED
- ✅ Cannot be selected for new invoice line items
- ✅ Existing invoices with this product remain unchanged
- ✅ Can be reactivated (manual DB operation or future API)

**Errors:**
- `404 NOT_FOUND` — Product does not exist
- `400 BAD_REQUEST` — Product already deactivated

---

## Service Implementation

### ProductService

```java
@Service
@RequiredArgsConstructor
public class ProductService {
    
    private final ProductRepository productRepository;
    private final CacheManager cacheManager;
    
    /**
     * Create new product for current tenant.
     */
    public Product create(CreateProductRequest request) {
        // Validate name uniqueness per tenant
        if (productRepository.existsByName(request.name())) {
            throw ResourceException.alreadyExists("Product with name already exists");
        }
        
        // Create product entity
        Product product = new Product();
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setTaxPercentage(request.taxPercentage());
        product.setHsnSacCode(request.hsnSacCode());
        product.setUnit(request.unit());
        product.setStatus(ProductStatus.ACTIVE);
        
        Product saved = productRepository.save(product);
        
        // Invalidate cache
        invalidateProductCache();
        
        return saved;
    }
    
    /**
     * Retrieve product by ID (tenant-scoped).
     */
    public Product getById(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> ResourceException.notFound("Product not found"));
    }
    
    /**
     * List all products with optional search and status filter (paginated).
     */
    public Page<ProductDTO> list(String search, ProductStatus status, Pageable pageable) {
        if (search != null && !search.isEmpty()) {
            String pattern = "%" + search.toLowerCase() + "%";
            if (status != null) {
                return productRepository.findByNameIgnoreCaseContainingAndStatus(
                    pattern, status, pageable
                ).map(ProductMapper::toDTO);
            }
            return productRepository.findByNameIgnoreCaseContaining(
                pattern, pageable
            ).map(ProductMapper::toDTO);
        }
        
        if (status != null) {
            return productRepository.findByStatus(status, pageable)
                .map(ProductMapper::toDTO);
        }
        
        return productRepository.findAll(pageable)
            .map(ProductMapper::toDTO);
    }
    
    /**
     * List ACTIVE products (alphabetically), typically for invoice creation dropdowns.
     * Result is cached (5 minute TTL).
     */
    @Cacheable(value = "activeProducts", cacheManager = "cacheManager", ttl = "300s")
    public List<ProductDTO> listActiveForInvoice() {
        return productRepository.findByStatus(ProductStatus.ACTIVE)
            .stream()
            .sorted(Comparator.comparing(Product::getName))
            .map(ProductMapper::toDTO)
            .collect(Collectors.toList());
    }
    
    /**
     * Update product information.
     * NOTE: Price and tax changes affect only new invoices, not historical line items.
     */
    public Product update(Long productId, UpdateProductRequest request) {
        Product product = getById(productId);
        
        // Update fields (allow null for optional fields)
        if (request.name() != null) {
            // Validate name uniqueness (excluding current product)
            if (!product.getName().equals(request.name()) && 
                productRepository.existsByName(request.name())) {
                throw ResourceException.alreadyExists("Product with name already exists");
            }
            product.setName(request.name());
        }
        
        if (request.description() != null) product.setDescription(request.description());
        if (request.price() != null) product.setPrice(request.price());
        if (request.taxPercentage() != null) product.setTaxPercentage(request.taxPercentage());
        if (request.hsnSacCode() != null) product.setHsnSacCode(request.hsnSacCode());
        if (request.unit() != null) product.setUnit(request.unit());
        
        Product saved = productRepository.save(product);
        
        // Invalidate cache
        invalidateProductCache();
        
        return saved;
    }
    
    /**
     * Deactivate product (prevent from new invoices, but keep for historical reference).
     */
    public void deactivate(Long productId) {
        Product product = getById(productId);
        
        if (product.getStatus() == ProductStatus.DEACTIVATED) {
            throw ResourceException.invalid("Product is already deactivated");
        }
        
        product.setStatus(ProductStatus.DEACTIVATED);
        productRepository.save(product);
        
        // Invalidate cache
        invalidateProductCache();
    }
    
    private void invalidateProductCache() {
        Cache cache = cacheManager.getCache("activeProducts");
        if (cache != null) cache.clear();
    }
}
```

---

## Permission & Authorization

**Required Permission:** `PERMISSION_BILLING_MANAGE`

- All product endpoints require this permission
- Enforced at controller level via `@Secured` annotation
- Tenant-scoped automatically via TenantContext

```java
@RestController
@RequestMapping("/api/v*/products")
@RequiredArgsConstructor
public class ProductController {
    
    @PostMapping
    @Secured("PERMISSION_BILLING_MANAGE")
    public ResponseEntity<ProductDTO> create(@RequestBody CreateProductRequest request) {
        // ...
    }
    
    @GetMapping("/active")
    public ResponseEntity<List<ProductDTO>> listActive() {
        // Public read for dropdown usage
    }
}
```

---

## Price & Tax Snapshot Design

### Why Snapshot Prices?

When an invoice line item is created, the **product's current price and tax are snapshotted**:

```java
// Invoice line item creation
LineItem lineItem = new LineItem();
lineItem.setProductId(product.getId());           // Reference
lineItem.setDescription(product.getName());       // Snapshot description
lineItem.setUnitPrice(product.getPrice());        // ⭐ Snapshot price
lineItem.setTaxPercentage(product.getTaxPercentage());  // ⭐ Snapshot tax
lineItem.setQuantity(request.getQuantity());
lineItem.calculateTotals();  // Computes tax & total amounts
```

### Benefits

| Benefit | Explanation |
|---------|-------------|
| **Historical Accuracy** | Invoice shows exact price paid at time of creation |
| **Audit Trail** | Product price history preserved via line items |
| **Simplicity** | No complex linked lookups during reporting |
| **Tax Compliance** | GST rate locked per invoice date (India compliance) |
| **Price Testing** | Can safely update products without affecting old invoices |

### Example Scenario

```
Timeline:
─────────────────────────────────────────────
May 1:  Create product "Hosting" at ₹99.99, 18% GST
May 15: Invoice-001 created, line item snapshotted at ₹99.99, 18%
May 20: Update product price to ₹109.99
May 25: Invoice-002 created, line item snapshotted at ₹109.99, 18%

Result:
─────────────────────────────────────────────
Invoice-001: Shows ₹99.99 (old price)
Invoice-002: Shows ₹109.99 (new price)
Product record: Shows ₹109.99 (current price)
```

---

## Tax Compliance (India)

### HSN/SAC Codes

**HSN (Harmonized System of Nomenclature):**
- For goods (physical products)
- Format: 8 or 10 digits (e.g., "7209.16.00")
- Example: Electronics, machinery, chemicals

**SAC (Services Accounting Code):**
- For services
- Format: 6 digits (e.g., "9983" for software services)
- Example: Software, consulting, hosting, support

**Common SAC Codes:**
| Service Type | Code | Tax Rate |
|---|---|---|
| Software development | 9983 | 18% |
| IT consulting | 9988 | 18% |
| Cloud/SaaS services | 9983 | 18% |
| Support services | 9983 | 18% |

### Tax Percentage Validation

- **Valid rates:** 0, 5, 12, 18, 28 (standard GST rates in India)
- Other rates possible for special cases
- Server-side validation: Must be between 0 and 100

---

## Units & Measurements

**Recommended Units:**
- **Time-based:** hrs, min, days, weeks, months, years
- **Data:** KB, MB, GB, TB
- **Quantity:** units, items, pieces, licenses
- **Weight:** kg, g, lb, oz
- **Distance:** km, m, miles
- **Services:** incidents, consultations, calls

**Custom Units:**
- Any text string allowed (not restricted)
- Examples: "API calls (millions)", "Transactions (per million)"
- Printed on invoices as-is

---

## Deactivation vs Deletion

### Why Never Delete Products?

```
Problem if deleted:
├─ Invoice historical integrity breaks
├─ Tax compliance issues (GST rate lost)
├─ Audit trail gaps
└─ Reporting inaccuracies

Solution: Deactivate instead
├─ Product marked DEACTIVATED
├─ Historical items reference product_id unchanged
├─ All product data preserved
└─ Filters hide from new invoice creation
```

### Deactivation Workflow

1. **Deactivate** product (status → DEACTIVATED)
2. **Existing invoices** continue to reference product ID
3. **New invoices** cannot select this product
4. **Reporting** still includes historical line items

---

## Caching Strategy

### Active Products Cache

```yaml
Cache: activeProducts
TTL: 5 minutes
Updated: On create, update, or deactivate
```

**Why Cache?**
- Frequently accessed for invoice creation dropdowns
- Read-heavy endpoint (no writes)
- Reduces database load in high-traffic scenarios

**Invalidation:**
```java
// Automatically cleared on mutations
product.deactivate();      // Clears cache
product.update();          // Clears cache
product.create();          // Clears cache
```

---

## Integration Points

### 1. With Invoicing

- Products selected for invoice line items
- Price/tax snapshotted at line item creation
- Cannot delete product, only deactivate

### 2. With Customers

- Products are tenant-scoped, not customer-scoped
- Any customer can be invoiced for any product

### 3. With Reporting

- Reports aggregate line items (not products directly)
- Revenue reports may group by product (future)
- Product deactivation doesn't affect historical reporting

---

## Best Practices

### 1. Price Management

✅ **DO:**
- Use consistent decimal places (e.g., 99.99)
- Document price update policy (e.g., "prices update 1st of month")
- Keep product description accurate

❌ **DON'T:**
- Delete products (deactivate instead)
- Expect price changes to affect old invoices
- Store quantities in price field (use quantity + price separately)

### 2. Tax Compliance

✅ **DO:**
- Always include HSN/SAC code for audit/compliance
- Use standard GST rates where applicable
- Validate tax percentage before saving

❌ **DON'T:**
- Apply different tax rates to same product (create variants instead)
- Forget to update tax rate when regulations change

### 3. Unit Selection

✅ **DO:**
- Use standard units (hrs, GB, units, etc.)
- Document custom units in description
- Use consistent units across related products

❌ **DON'T:**
- Use inconsistent units (e.g., some "hrs" others "hours")
- Leave unit blank for clarity-critical products

---

## Future Enhancements

🔜 **Product Categories** — Organize products into groups  
🔜 **Bundled Products** — Package multiple products as one  
🔜 **Tiered Pricing** — Volume-based discounts  
🔜 **Product Variants** — Colors, sizes, configurations  
🔜 **Stock Tracking** — Inventory management  
🔜 **Supplier Integration** — Auto-purchase when low stock  
🔜 **Bulk Import** — CSV upload for products  
🔜 **Revenue Recognition** — Track deferred revenue by product  

---

## Related Documentation

- [Customer Management - Customers](./customers.md)
- [Invoicing Module - Business Invoices](./invoicing.md)
- [Payment Recording](./payments.md)
- [Reporting & Analytics](./reporting.md)
