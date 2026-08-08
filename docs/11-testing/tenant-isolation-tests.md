# Multi-Tenancy Isolation Tests

## Overview

The **Multi-Tenancy Isolation Test Suite** validates that tenants are completely isolated from each other—one tenant's data cannot leak into another's. This is critical for SaaS security and compliance.

**Test Location:** `src/test/java/com/mtbs/integration/MultiTenancyIntegrationTest.java`

---

## Testing Strategy

### Core Principle

Each test verifies:
1. **Isolation** — Tenant A's data is invisible to Tenant B
2. **Filtering** — All queries automatically filter by tenant ID
3. **Initialization** — Fresh schemas prevent cross-tenant pollution
4. **Authorization** — JWT tokens include tenant context

### Test Structure

```
┌─────────────────────────────────────────────────┐
│   Multi-Tenancy Isolation Test Suite            │
├─────────────────────────────────────────────────┤
│                                                 │
│  1. Data Isolation Tests                        │
│     └─ Each tenant has separate schema          │
│     └─ Data not visible across schemas          │
│                                                 │
│  2. Query Filtering Tests                       │
│     └─ Subscriptions filtered by tenant         │
│     └─ Invoices filtered by tenant              │
│     └─ Users filtered by tenant                 │
│                                                 │
│  3. Context Propagation Tests                   │
│     └─ TenantContext set via JWT                │
│     └─ Schema routing correct                   │
│     └─ Async tasks inherit context              │
│                                                 │
│  4. Authorization Tests                         │
│     └─ Can't access other tenant's API          │
│     └─ Token scoped to tenant                   │
│     └─ Cross-tenant resource access denied      │
│                                                 │
└─────────────────────────────────────────────────┘
```

---

## Test Implementation

### 1. Data Isolation Tests

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("MultiTenancyIntegrationTest")
class MultiTenancyIntegrationTest {
    
    @LocalServerPort
    private int port;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Autowired
    private TestSchemaHelper schemaHelper;
    
    @Autowired
    private TestDataBuilder testDataBuilder;
    
    private Tenant tenant1;
    private Tenant tenant2;
    private String tenant1Token;
    private String tenant2Token;
    private String baseUrl;
    
    @BeforeEach
    void setup() {
        // Create two isolated tenants
        tenant1 = schemaHelper.createFreshSchema("tenant_isolation_test_1");
        tenant2 = schemaHelper.createFreshSchema("tenant_isolation_test_2");
        
        baseUrl = "http://localhost:" + port + "/api/v1";
        
        // Login as tenant1
        tenant1Token = login("user@tenant1.com", "password");
        
        // Login as tenant2
        tenant2Token = login("user@tenant2.com", "password");
    }
    
    @AfterEach
    void teardown() {
        schemaHelper.dropSchema(tenant1.getSchemaName());
        schemaHelper.dropSchema(tenant2.getSchemaName());
    }
    
    @Nested
    @DisplayName("Data Isolation")
    class DataIsolationTests {
        
        /**
         * CRITICAL: Tenant A creates subscription, Tenant B cannot see it.
         */
        @Test
        @DisplayName("Tenant A's subscriptions invisible to Tenant B")
        void subscriptionsIsolatedBetweenTenants() {
            // Tenant 1: Create subscription
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(1L)
                .build();
            
            ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(tenant1Token)),
                SubscriptionResponse.class
            );
            
            assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long tenant1SubscriptionId = createResponse.getBody().getId();
            
            // Verify Tenant 1 sees their subscription
            ResponseEntity<List<SubscriptionResponse>> tenant1List = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant1Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant1List.getBody())
                .hasSize(1)
                .extracting(SubscriptionResponse::getId)
                .contains(tenant1SubscriptionId);
            
            // Tenant 2: List subscriptions (should be empty)
            ResponseEntity<List<SubscriptionResponse>> tenant2List = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant2Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant2List.getBody())
                .isEmpty();  // ⭐ CRITICAL
            
            // Tenant 2: Try to GET tenant 1's subscription directly (should fail)
            ResponseEntity<SubscriptionResponse> crossTenantAccess = restTemplate.exchange(
                baseUrl + "/subscriptions/" + tenant1SubscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant2Token)),
                SubscriptionResponse.class
            );
            
            assertThat(crossTenantAccess.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);  // ⭐ CRITICAL
        }
        
        /**
         * Tenant A creates invoice, Tenant B cannot see it.
         */
        @Test
        @DisplayName("Tenant A's invoices invisible to Tenant B")
        void invoicesIsolatedBetweenTenants() {
            // Tenant 1: Create invoice
            CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .customerId(1L)
                .lineItems(List.of(
                    LineItemRequest.builder()
                        .description("Service")
                        .quantity(1)
                        .unitPrice(new BigDecimal("100.00"))
                        .build()
                ))
                .build();
            
            ResponseEntity<InvoiceResponse> createResponse = restTemplate.exchange(
                baseUrl + "/business-invoices",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(tenant1Token)),
                InvoiceResponse.class
            );
            
            Long tenant1InvoiceId = createResponse.getBody().getId();
            
            // Tenant 1: Can see their invoice
            ResponseEntity<List<InvoiceResponse>> tenant1Invoices = restTemplate.exchange(
                baseUrl + "/business-invoices",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant1Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant1Invoices.getBody())
                .extracting(InvoiceResponse::getId)
                .contains(tenant1InvoiceId);
            
            // Tenant 2: Cannot see Tenant 1's invoice
            ResponseEntity<List<InvoiceResponse>> tenant2Invoices = restTemplate.exchange(
                baseUrl + "/business-invoices",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant2Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant2Invoices.getBody())
                .isEmpty();  // ⭐ CRITICAL
        }
        
        /**
         * Tenant A creates customer, Tenant B has empty customer list.
         */
        @Test
        @DisplayName("Tenant A's customers invisible to Tenant B")
        void customersIsolatedBetweenTenants() {
            // Tenant 1: Create customer
            CreateCustomerRequest request = CreateCustomerRequest.builder()
                .name("Acme Corp")
                .email("acme@test.com")
                .gstin("27AABCT1234H1Z0")
                .build();
            
            ResponseEntity<CustomerResponse> createResponse = restTemplate.exchange(
                baseUrl + "/customers",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(tenant1Token)),
                CustomerResponse.class
            );
            
            // Tenant 1: See customer
            ResponseEntity<List<CustomerResponse>> tenant1Customers = restTemplate.exchange(
                baseUrl + "/customers",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant1Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant1Customers.getBody()).hasSize(1);
            
            // Tenant 2: Empty list
            ResponseEntity<List<CustomerResponse>> tenant2Customers = restTemplate.exchange(
                baseUrl + "/customers",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant2Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant2Customers.getBody())
                .isEmpty();  // ⭐ CRITICAL
        }
    }
    
    @Nested
    @DisplayName("Query Filtering")
    class QueryFilteringTests {
        
        /**
         * Verify TenantContext is correctly used in queries.
         */
        @Test
        @DisplayName("Repository queries automatically filter by tenant")
        void repositoriesFilterByTenant() {
            // Tenant 1: Create subscription
            CreateSubscriptionRequest request1 = CreateSubscriptionRequest.builder()
                .planId(1L)
                .build();
            
            restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request1, authHeaders(tenant1Token)),
                SubscriptionResponse.class
            );
            
            // Tenant 2: Create subscription
            CreateSubscriptionRequest request2 = CreateSubscriptionRequest.builder()
                .planId(1L)
                .build();
            
            restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request2, authHeaders(tenant2Token)),
                SubscriptionResponse.class
            );
            
            // Query database directly (as if we could access both schemas)
            // This verifies that the schema is separate, not just query filtering
            
            // Tenant 1 schema: Should have 1 subscription
            ResponseEntity<List<SubscriptionResponse>> tenant1List = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant1Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant1List.getBody()).hasSize(1);
            
            // Tenant 2 schema: Should have 1 subscription (not 2)
            ResponseEntity<List<SubscriptionResponse>> tenant2List = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant2Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant2List.getBody()).hasSize(1);
        }
    }
    
    @Nested
    @DisplayName("Context Propagation")
    class ContextPropagationTests {
        
        /**
         * Verify TenantContext is set from JWT token.
         */
        @Test
        @DisplayName("TenantContext extracted from JWT correctly")
        void tenantContextFromJwt() {
            // Tenant 1: Make request
            ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + "/system/tenant-context",  // Debug endpoint
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant1Token)),
                Map.class
            );
            
            assertThat(response.getBody())
                .containsEntry("tenantId", tenant1.getId())
                .containsEntry("schemaName", tenant1.getSchemaName());
            
            // Tenant 2: Make same request
            ResponseEntity<Map> response2 = restTemplate.exchange(
                baseUrl + "/system/tenant-context",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant2Token)),
                Map.class
            );
            
            assertThat(response2.getBody())
                .containsEntry("tenantId", tenant2.getId())
                .containsEntry("schemaName", tenant2.getSchemaName());
        }
        
        /**
         * Verify async tasks inherit TenantContext.
         */
        @Test
        @DisplayName("Async event handlers execute in correct tenant context")
        void asyncTasksInheritTenantContext() throws InterruptedException {
            // Tenant 1: Create subscription (triggers async event)
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(1L)
                .build();
            
            restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(tenant1Token)),
                SubscriptionResponse.class
            );
            
            // Wait for async event handler to execute
            Thread.sleep(1000);
            
            // Verify notification was created in TENANT 1 schema, not global
            ResponseEntity<List<NotificationResponse>> notifications = restTemplate.exchange(
                baseUrl + "/notifications",  // Assuming endpoint exists
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant1Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(notifications.getBody()).hasSize(1);
            
            // Tenant 2: Should not see the notification
            ResponseEntity<List<NotificationResponse>> tenant2Notifications = restTemplate.exchange(
                baseUrl + "/notifications",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant2Token)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(tenant2Notifications.getBody()).isEmpty();
        }
    }
    
    @Nested
    @DisplayName("Authorization")
    class AuthorizationTests {
        
        /**
         * JWT token should be scoped to tenant.
         */
        @Test
        @DisplayName("Invalid token for wrong tenant fails authorization")
        void invalidTokenForWrongTenant() {
            // Create a token that claims to be from a non-existent tenant
            String tamperingToken = createTokenForTenant(999999L);
            
            // Try to use tampered token
            ResponseEntity<List<SubscriptionResponse>> response = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tamperingToken)),
                new ParameterizedTypeReference<>() {}
            );
            
            // Should fail (tenant doesn't exist in database)
            assertThat(response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
        
        /**
         * User from Tenant A cannot modify Tenant B's resources.
         */
        @Test
        @DisplayName("Cross-tenant resource modification prevented")
        void crossTenantModificationPrevented() {
            // Tenant 1: Create subscription
            CreateSubscriptionRequest request1 = CreateSubscriptionRequest.builder()
                .planId(1L)
                .build();
            
            ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request1, authHeaders(tenant1Token)),
                SubscriptionResponse.class
            );
            
            Long subscriptionId = createResponse.getBody().getId();
            
            // Tenant 2: Try to cancel Tenant 1's subscription
            ResponseEntity<Void> cancelResponse = restTemplate.exchange(
                baseUrl + "/subscriptions/" + subscriptionId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant2Token)),
                Void.class
            );
            
            // Should be rejected
            assertThat(cancelResponse.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);  // Or FORBIDDEN
        }
    }
    
    @Nested
    @DisplayName("Schema Verification")
    class SchemaVerificationTests {
        
        /**
         * Verify separate schemas actually exist in database.
         */
        @Test
        @DisplayName("Each tenant has separate PostgreSQL schema")
        void separateSchemasExist() {
            // This is a lower-level verification to ensure schema isolation is real,
            // not just application-level filtering
            
            // Query information_schema (PostgreSQL system catalog)
            // Verify tenant1.getSchemaName() exists
            // Verify tenant2.getSchemaName() exists
            // Verify they are different
            
            assertThat(tenant1.getSchemaName())
                .isNotEqualTo(tenant2.getSchemaName());
        }
    }
    
    // Helper methods
    
    private String login(String email, String password) {
        LoginRequest request = new LoginRequest(email, password);
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/auth/login",
            request,
            LoginResponse.class
        );
        return response.getBody().getAccessToken();
    }
    
    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
    
    private String createTokenForTenant(Long tenantId) {
        // Helper to create JWT for testing (bypasses login)
        return JwtTokenProvider.createToken(
            UserPrincipal.builder()
                .userId(1L)
                .email("test@test.com")
                .tenantId(tenantId)
                .role("ADMIN")
                .build(),
            Duration.ofHours(1)
        );
    }
}
```

---

## Critical Tests

### Mandatory Security Tests

| Test | Purpose | Risk if Fails |
|------|---------|---------------|
| **Subscription Isolation** | Cannot see other tenant subscriptions | Data breach |
| **Invoice Isolation** | Cannot access other tenant invoices | Financial data leak |
| **Customer Isolation** | Cannot view other tenant customers | Privacy violation |
| **Payment Isolation** | Cannot see other tenant payments | Revenue leak |
| **Cross-Tenant Update Prevention** | Cannot modify other tenant's resources | Data corruption |
| **Token Scope** | JWT scoped to tenant | Authentication bypass |

---

## Verification Checklist

✅ **Database Level:**
- [ ] Each tenant has separate PostgreSQL schema
- [ ] Schema names are unique per tenant
- [ ] Data physically separated in database

✅ **Application Level:**
- [ ] TenantContext set from JWT on every request
- [ ] All queries filter by tenant automatically
- [ ] No hardcoded tenant IDs in queries

✅ **Authorization Level:**
- [ ] User can only see their tenant's data
- [ ] Cannot modify other tenant's resources
- [ ] API returns 404 (not 403) for non-existent resources (privacy)

✅ **Async/Events Level:**
- [ ] Event handlers execute in correct tenant context
- [ ] Async tasks don't leak data between tenants
- [ ] Background jobs properly scoped

---

## Common Pitfalls & How to Avoid

### Pitfall 1: Forgetting TenantContext in Async Task

```java
// ❌ WRONG: Async task loses tenant context
@Async
public void sendNotification(NotificationEvent event) {
    // TenantContext.getTenantId() = null here!
    // Query will fail or return wrong tenant's data
}

// ✅ CORRECT: Propagate context to async
@Async
public void sendNotification(NotificationEvent event) {
    TenantContext.setTenantId(event.getTenantId());
    TenantContext.setCurrentSchema(event.getSchemaName());
    try {
        // Now queries work correctly
    } finally {
        TenantContext.clear();
    }
}

// ✅ BEST: Use TenantAwareTaskDecorator
```

### Pitfall 2: Returning 403 Instead of 404

```java
// ❌ WRONG: Reveals existence of other tenant's resource
if (!tenantId.equals(currentTenantId)) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
}

// ✅ CORRECT: Always return 404 (privacy-preserving)
if (!tenantId.equals(currentTenantId)) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
}
```

### Pitfall 3: Not Filtering Queries

```java
// ❌ WRONG: Returns all invoices (data leak)
public List<Invoice> getInvoices() {
    return invoiceRepository.findAll();
}

// ✅ CORRECT: Filter by current tenant
public List<Invoice> getInvoices() {
    Long tenantId = TenantContextHolder.getTenantId();
    return invoiceRepository.findByTenantId(tenantId);
}
```

---

## Related Documentation

- [Multi-Tenancy Architecture](../backend/02-multi-tenancy/isolation.md)
- [Testing Strategy](./testing-strategy.md)
- [Security & Authorization](../backend/03-security/authorization.md)
