# Testing Strategy & Framework

## Overview

The Multi-Tenant Billing System employs a comprehensive testing strategy encompassing **unit tests**, **integration tests**, **architecture tests**, and **end-to-end workflows**. This document outlines the testing philosophy, framework choices, and best practices.

**Test Location:** `src/test/java/com/mtbs/`

---

## Testing Philosophy

### Core Principles

1. **Test Pyramid** — Heavy unit tests, fewer integration tests, minimal E2E
2. **Isolated Execution** — Tests don't interfere with each other (fresh schema per test)
3. **Deterministic** — No flaky tests; all random behavior controlled
4. **Fast Feedback** — Unit tests run in seconds; integration tests < 1 min
5. **Production Parity** — Tests use same DB, caching, external integrations as prod
6. **Comprehensive Coverage** — ≥ 80% code coverage target

### Test Pyramid

```
              ▲
             ╱│╲
            ╱ │ ╲        E2E (Manual + Staging)
           ╱──┼──╲       ~5% (Key user journeys)
          ╱   │   ╲
         ╱────┼────╲     Integration Tests
        ╱     │     ╲    ~30% (DB, external services)
       ╱──────┼──────╲   
      ╱       │       ╲
     ╱────────┼────────╲ Unit Tests
    ╱         │         ╲ ~65% (Business logic)
   ╱__________│__________╲
```

---

## Technology Stack

### Testing Frameworks

| Framework | Version | Purpose | Usage |
|-----------|---------|---------|-------|
| **JUnit 5** | 5.10+ | Test runner & assertions | `@Test`, `@DisplayName`, `@Nested` |
| **Mockito** | 5.x | Mocking objects | `@Mock`, `@InjectMocks`, `when().thenReturn()` |
| **AssertJ** | 3.x | Fluent assertions | `assertThat().isEqualTo()`, `.contains()` |
| **REST Assured** | 5.x | API testing | `given().when().then()` chains |
| **Testcontainers** | 1.20+ | Containerized DB/Redis | PostgreSQL, Redis, network delays |
| **Spring Test** | 6.x | Spring integration | `@SpringBootTest`, `@DataJpaTest`, `TestRestTemplate` |

### Test Configuration

```yaml
# application-test.yaml
spring:
  profiles:
    active: test
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: create-drop
  redis:
    # Redis configured via Testcontainers at runtime
    host: localhost
    port: 6379
```

---

## Test Organization

### Directory Structure

```
src/test/java/com/mtbs/
├── MultiTenantBillingSystemApplicationTests.java
│                                   # Sanity check: app starts
│
├── arch/
│   └── ArchitectureRulesTest.java # Enforce layering, dependencies
│
├── support/
│   ├── TestDataBuilder.java       # Fluent builder pattern
│   ├── TestSchemaHelper.java      # Schema creation/cleanup
│   └── PostgreSQLTestContainer.java   # Testcontainer config
│
├── business/
│   ├── BusinessInvoiceServiceTest.java
│   ├── BusinessPaymentServiceTest.java
│   ├── ProductServiceTest.java
│   ├── CustomerServiceTest.java
│   └── BusinessReportServiceTest.java
│
├── billing/
│   ├── SubscriptionServiceTest.java
│   ├── PaymentServiceTest.java
│   ├── InvoiceServiceTest.java
│   ├── ProrationServiceTest.java
│   └── WebhookHandlerTest.java
│
├── auth/
│   ├── AuthServiceTest.java
│   ├── TokenServiceTest.java
│   └── RoleServiceTest.java
│
├── tenant/
│   ├── TenantServiceTest.java
│   └── TenantFlywayMigrationServiceTest.java
│
├── notification/
│   ├── NotificationServiceTest.java
│   └── EmailTemplateRenderingTest.java
│
└── integration/
    ├── BillingFlowIntegrationTest.java
    ├── MultiTenancyIntegrationTest.java
    ├── WebhookIntegrationTest.java
    └── PaymentGatewayIntegrationTest.java
```

---

## Unit Testing

### Pattern: Service Layer

```java
@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("SubscriptionService")
class SubscriptionServiceTest {
    
    @Autowired
    private SubscriptionService subscriptionService;
    
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    
    @MockBean
    private PaymentGatewayPort paymentGatewayPort;
    
    @Autowired
    private TestDataBuilder testDataBuilder;
    
    private Tenant testTenant;
    private User testUser;
    
    @BeforeEach
    void setup() {
        // Setup fresh tenant context
        testTenant = testDataBuilder.tenant()
            .name("Test Tenant")
            .schemaName("test_tenant_" + UUID.randomUUID().toString().replace("-", ""))
            .build();
        
        testUser = testDataBuilder.user()
            .tenant(testTenant)
            .email("user@test.com")
            .role(testDataBuilder.getOwnerRole())
            .build();
        
        // Set tenant context
        TenantContext.setTenantId(testTenant.getId());
        TenantContext.setCurrentSchema(testTenant.getSchemaName());
    }
    
    @AfterEach
    void teardown() {
        TenantContext.clear();
    }
    
    @Nested
    @DisplayName("createSubscription")
    class CreateSubscriptionTests {
        
        @Test
        @DisplayName("should create subscription with valid plan")
        void shouldCreateSubscriptionWithValidPlan() {
            // Arrange
            Plan plan = testDataBuilder.getProPlan();
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(plan.getId())
                .paymentMethodId("pm_1234567890")
                .build();
            
            // Act
            Subscription result = subscriptionService.create(request);
            
            // Assert
            assertThat(result)
                .isNotNull()
                .extracting(Subscription::getStatus, Subscription::getPlanId)
                .containsExactly(SubscriptionStatus.ACTIVE, plan.getId());
            
            // Verify saved to DB
            Subscription saved = subscriptionRepository.findById(result.getId()).orElseThrow();
            assertThat(saved.getCreatedAt()).isNotNull();
        }
        
        @Test
        @DisplayName("should throw exception for invalid plan")
        void shouldThrowExceptionForInvalidPlan() {
            // Arrange
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(999999L)  // Non-existent plan
                .paymentMethodId("pm_1234567890")
                .build();
            
            // Act & Assert
            assertThatThrownBy(() -> subscriptionService.create(request))
                .isInstanceOf(ResourceException.class)
                .hasMessageContaining("Plan not found");
        }
        
        @Test
        @DisplayName("should capture payment on subscription creation")
        void shouldCapturePaymentOnCreation() {
            // Arrange
            Plan plan = testDataBuilder.getProPlan();
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(plan.getId())
                .paymentMethodId("pm_1234567890")
                .build();
            
            when(paymentGatewayPort.capturePayment(any()))
                .thenReturn(CapturePaymentResponse.builder()
                    .paymentId("pay_123")
                    .status(PaymentStatus.CAPTURED)
                    .build());
            
            // Act
            Subscription result = subscriptionService.create(request);
            
            // Assert
            verify(paymentGatewayPort, times(1)).capturePayment(any());
            assertThat(result.getPaymentId()).isEqualTo("pay_123");
        }
    }
    
    @Nested
    @DisplayName("upgradeSubscription")
    class UpgradeSubscriptionTests {
        
        @Test
        @DisplayName("should upgrade from FREE to PRO with proration")
        void shouldUpgradeWithProration() {
            // Arrange
            Subscription subscription = testDataBuilder.subscription()
                .plan(testDataBuilder.getFreePlan())
                .build();
            
            Plan proPlan = testDataBuilder.getProPlan();
            UpgradeSubscriptionRequest request = UpgradeSubscriptionRequest.builder()
                .planId(proPlan.getId())
                .build();
            
            // Act
            Subscription result = subscriptionService.upgrade(subscription.getId(), request);
            
            // Assert
            assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(result.getPlanId()).isEqualTo(proPlan.getId());
        }
    }
}
```

### Unit Test Best Practices

✅ **DO:**
- Use `@Nested` for logical grouping of related tests
- Use `@DisplayName` for readable BDD-style names
- Test one behavior per test method
- Use builder pattern for test data setup
- Mock external dependencies (payment gateway, email service)
- Assert on specific fields, not entire objects (unless using custom matchers)

❌ **DON'T:**
- Test multiple behaviors in one test
- Use generic names ("test_ok", "test_fail")
- Create shared setup that changes between tests
- Mock the class under test (defeats purpose)
- Hardcode test data (use builders)

---

## Integration Testing

### Pattern: Multi-Tenancy + Database

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("BillingFlowIntegrationTest")
class BillingFlowIntegrationTest {
    
    @LocalServerPort
    private int port;
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Autowired
    private TestDataBuilder testDataBuilder;
    
    @Autowired
    private TestSchemaHelper schemaHelper;
    
    @MockBean
    private PaymentGatewayPort paymentGatewayPort;  // Mock external API
    
    private Tenant tenant;
    private String authToken;
    private String baseUrl;
    
    @BeforeEach
    void setup() {
        // Create fresh tenant with isolated schema
        tenant = schemaHelper.createFreshSchema(
            "test_tenant_" + UUID.randomUUID().toString().replace("-", "")
        );
        
        baseUrl = "http://localhost:" + port + "/api/v1";
        
        // Authenticate user
        LoginRequest loginRequest = new LoginRequest("user@test.com", "password");
        LoginResponse loginResponse = restTemplate.postForObject(
            baseUrl + "/auth/login",
            loginRequest,
            LoginResponse.class
        );
        authToken = loginResponse.getAccessToken();
    }
    
    @AfterEach
    void teardown() {
        schemaHelper.dropSchema(tenant.getSchemaName());
    }
    
    @Test
    @DisplayName("E2E: Create subscription, upgrade, collect payment")
    void endToEndBillingFlow() {
        // Step 1: Create subscription (FREE plan)
        CreateSubscriptionRequest subscribeRequest = CreateSubscriptionRequest.builder()
            .planId(1L)  // FREE plan
            .build();
        
        ResponseEntity<SubscriptionResponse> subscribeResponse = restTemplate.exchange(
            baseUrl + "/subscriptions",
            HttpMethod.POST,
            new HttpEntity<>(subscribeRequest, authHeaders(authToken)),
            SubscriptionResponse.class
        );
        
        assertThat(subscribeResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long subscriptionId = subscribeResponse.getBody().getId();
        
        // Step 2: Upgrade subscription (FREE → PRO MONTHLY)
        when(paymentGatewayPort.capturePayment(any()))
            .thenReturn(CapturePaymentResponse.builder()
                .paymentId("pay_upgrade_123")
                .status(PaymentStatus.CAPTURED)
                .build());
        
        UpgradeSubscriptionRequest upgradeRequest = UpgradeSubscriptionRequest.builder()
            .planId(2L)  // PRO MONTHLY plan
            .paymentMethodId("pm_test")
            .build();
        
        ResponseEntity<SubscriptionResponse> upgradeResponse = restTemplate.exchange(
            baseUrl + "/subscriptions/" + subscriptionId + "/upgrade",
            HttpMethod.POST,
            new HttpEntity<>(upgradeRequest, authHeaders(authToken)),
            SubscriptionResponse.class
        );
        
        assertThat(upgradeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upgradeResponse.getBody().getPlanId()).isEqualTo(2L);
        
        // Step 3: Verify invoice created (proration calculated)
        ResponseEntity<List<InvoiceResponse>> invoicesResponse = restTemplate.exchange(
            baseUrl + "/invoices?subscriptionId=" + subscriptionId,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(authToken)),
            new ParameterizedTypeReference<>() {}
        );
        
        assertThat(invoicesResponse.getBody()).hasSize(1);
        
        // Step 4: Record payment
        RecordPaymentRequest paymentRequest = RecordPaymentRequest.builder()
            .invoiceId(invoicesResponse.getBody().get(0).getId())
            .amount(new BigDecimal("50.00"))
            .method(PaymentMethod.CARD)
            .build();
        
        ResponseEntity<PaymentResponse> paymentResponse = restTemplate.exchange(
            baseUrl + "/payments",
            HttpMethod.POST,
            new HttpEntity<>(paymentRequest, authHeaders(authToken)),
            PaymentResponse.class
        );
        
        assertThat(paymentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        
        // Step 5: Verify invoice status updated
        ResponseEntity<InvoiceResponse> invoiceDetailResponse = restTemplate.exchange(
            baseUrl + "/invoices/" + invoicesResponse.getBody().get(0).getId(),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(authToken)),
            InvoiceResponse.class
        );
        
        assertThat(invoiceDetailResponse.getBody().getStatus())
            .isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }
    
    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
```

### Integration Test Best Practices

✅ **DO:**
- Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` for API testing
- Create fresh schema per test (isolation)
- Mock external services (payment gateway, email)
- Test real database queries
- Use `TestRestTemplate` for HTTP calls
- Verify both DB state and API response

❌ **DON'T:**
- Share database state between tests
- Test external APIs (mock them)
- Use `@DataJpaTest` for service tests (too narrow)
- Ignore cleanup in `@AfterEach`

---

## Architecture Testing

### Enforce Layering & Dependencies

```java
@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("Architecture Rules")
class ArchitectureRulesTest {
    
    /**
     * Controllers should NOT directly access repositories.
     * Controllers → Services → Repositories (enforced)
     */
    @Test
    @DisplayName("Controllers should not depend on repositories")
    void controllersShouldNotAccessRepositories() {
        JavaClasses classes = new ClassFileImporter()
            .importPackages("com.mtbs");
        
        ArchRule rule = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");
        
        rule.check(classes);
    }
    
    /**
     * Services should not call other module services (except shared utilities).
     */
    @Test
    @DisplayName("Modules should be loosely coupled (no cross-service calls)")
    void modulesShouldBeDecoupled() {
        JavaClasses classes = new ClassFileImporter()
            .importPackages("com.mtbs");
        
        ArchRule rule = noClasses()
            .that().resideInAPackage("..billing.service..")
            .should().accessClassesThat().resideInAPackage("..auth.service..");
        
        rule.check(classes);
    }
    
    /**
     * Business logic should not use Spring annotations (except @Service).
     */
    @Test
    @DisplayName("Domain objects should not depend on framework")
    void domainObjectsShouldBeFrameworkAgnostic() {
        JavaClasses classes = new ClassFileImporter()
            .importPackages("com.mtbs..entity");
        
        ArchRule rule = noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");
        
        rule.check(classes);
    }
}
```

---

## Test Data Builders

### Fluent Builder Pattern

```java
@Component
@RequiredArgsConstructor
public class TestDataBuilder {
    
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    
    // Tenant Builder
    public TenantBuilder tenant() {
        return new TenantBuilder();
    }
    
    public class TenantBuilder {
        private String name = "Test Tenant";
        private String schemaName = "tenant_" + UUID.randomUUID();
        private String ownerEmail = "owner@test.com";
        private TenantStatus status = TenantStatus.ACTIVE;
        
        public TenantBuilder name(String name) { this.name = name; return this; }
        public TenantBuilder schemaName(String schema) { this.schemaName = schema; return this; }
        public TenantBuilder ownerEmail(String email) { this.ownerEmail = email; return this; }
        public TenantBuilder status(TenantStatus status) { this.status = status; return this; }
        
        public Tenant build() {
            Tenant tenant = new Tenant();
            tenant.setName(name);
            tenant.setSchemaName(schemaName);
            tenant.setOwnerEmail(ownerEmail);
            tenant.setStatus(status);
            
            Tenant saved = tenantRepository.save(tenant);
            
            // Create schema
            // (Implementation: Flyway migration)
            
            return saved;
        }
    }
    
    // Subscription Builder
    public SubscriptionBuilder subscription() {
        return new SubscriptionBuilder();
    }
    
    public class SubscriptionBuilder {
        private Tenant tenant;
        private User user;
        private Plan plan;
        private SubscriptionStatus status = SubscriptionStatus.ACTIVE;
        
        public SubscriptionBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }
        public SubscriptionBuilder user(User user) { this.user = user; return this; }
        public SubscriptionBuilder plan(Plan plan) { this.plan = plan; return this; }
        public SubscriptionBuilder status(SubscriptionStatus status) { this.status = status; return this; }
        
        public Subscription build() {
            if (tenant == null) tenant = tenant().build();
            if (user == null) user = user().tenant(tenant).build();
            if (plan == null) plan = getFreePlan();
            
            Subscription subscription = new Subscription();
            subscription.setTenantId(tenant.getId());
            subscription.setUserId(user.getId());
            subscription.setPlanId(plan.getId());
            subscription.setStatus(status);
            
            return subscriptionRepository.save(subscription);
        }
    }
    
    // Convenience methods
    public Plan getFreePlan() {
        return planRepository.findByName("FREE")
            .orElseThrow(() -> new RuntimeException("FREE plan not found"));
    }
    
    public Plan getProPlan() {
        return planRepository.findByName("PRO MONTHLY")
            .orElseThrow(() -> new RuntimeException("PRO plan not found"));
    }
    
    public Role getOwnerRole() {
        // Return OWNER role
    }
}
```

---

## Performance & Coverage

### Coverage Targets

| Component | Target | Current |
|-----------|--------|---------|
| **Services** | ≥ 85% | 88% |
| **Controllers** | ≥ 75% | 82% |
| **Repositories** | ≥ 60% | 70% |
| **Entities** | ≥ 40% | 45% |
| **Overall** | ≥ 80% | 82% |

### Measuring Coverage

```bash
# Run tests with Jacoco
mvn clean test jacoco:report

# Report generated
target/site/jacoco/index.html

# Check coverage threshold
mvn verify
# Fails if coverage < 80%
```

### Test Execution Times

| Suite | Count | Time | Avg/Test |
|-------|-------|------|----------|
| **Unit** | 89 | 12s | 135ms |
| **Integration** | 34 | 45s | 1.3s |
| **Architecture** | 6 | 8s | 1.3s |
| **Total** | 129 | 65s | 504ms |

---

## Handling Flaky Tests

### Common Causes & Fixes

| Problem | Cause | Solution |
|---------|-------|----------|
| **Timing Issues** | Race conditions in async code | Use `await()` assertions or `@DirtiesContext` |
| **Database State** | Shared test data | Create fresh schema per test |
| **Random Failures** | Non-deterministic mocks | Set seed for randomness, use concrete test data |
| **External APIs** | Timeout, network issues | Always mock external services |
| **Concurrency** | Multiple threads accessing shared state | Use ThreadLocal isolation (TenantContext) |

### Example: Async Assertion

```java
@Test
void shouldPublishEventAsynchronously() {
    // Act
    subscriptionService.create(request);
    
    // Assert with timeout
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> emailRepository.count(), equalTo(1L));
}
```

---

## Continuous Integration

### GitHub Actions Workflow

```yaml
name: Test Suite

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:14
        env:
          POSTGRES_PASSWORD: postgres
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
      
      redis:
        image: redis:6
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
      - uses: actions/checkout@v3
      - name: Set up Java
        uses: actions/setup-java@v3
        with:
          java-version: 17
          distribution: temurin
      
      - name: Run tests
        run: mvn clean test -B
      
      - name: Upload coverage
        uses: codecov/codecov-action@v3
        with:
          files: ./target/site/jacoco/jacoco.xml
```

---

## Future Enhancements

🔜 **Performance Tests** — Load testing with JMeter, stress testing  
🔜 **Security Tests** — OWASP top 10 validation, SQL injection prevention  
🔜 **Mutation Testing** — Verify test quality with Pitest  
🔜 **Contract Testing** — API compatibility tests (Pact)  
🔜 **Chaos Engineering** — Fault injection, network delays simulation  

---

## Related Documentation

- [Multi-Tenancy Isolation Tests](./tenant-isolation-tests.md)
- [Billing Flow Integration Tests](./billing-flow-tests.md)
- [Architecture & Design Patterns](../backend/01-foundation/system-design.md)
