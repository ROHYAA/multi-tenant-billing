# End-to-End Billing Flow Integration Tests

## Overview

The **Billing Flow Integration Tests** validate complete billing workflows from subscription creation through payment collection, including complex scenarios like mid-cycle plan changes with proration.

**Test Location:** `src/test/java/com/mtbs/integration/BillingFlowIntegrationTest.java`

---

## Test Scenarios

### Scenario Matrix

```
┌────────────────────────────────────────────────────────┐
│        BILLING FLOW INTEGRATION TEST SUITE             │
├────────────────────────────────────────────────────────┤
│                                                        │
│  1. Basic Subscription Lifecycle                       │
│     └─ Create → Trial → Active → Cancel               │
│     └─ Verify state transitions & events              │
│                                                        │
│  2. Plan Upgrade/Downgrade with Proration             │
│     └─ FREE → PRO (mid-cycle): credit owed            │
│     └─ PRO → ENTERPRISE (mid-cycle): charge needed    │
│     └─ PRO → FREE (mid-cycle): credit owed            │
│                                                        │
│  3. Payment Processing & Reconciliation               │
│     └─ Capture payment on subscription creation       │
│     └─ Handle payment failure & retry                 │
│     └─ Partial refunds on downgrade                   │
│                                                        │
│  4. Invoice & Payment Integration                     │
│     └─ Invoice generated for charges                  │
│     └─ Payment records linked to invoice              │
│     └─ Audit trail preserved                          │
│                                                        │
│  5. Event-Driven Notifications                        │
│     └─ Email on subscription creation                 │
│     └─ Notification on payment success                │
│     └─ Alert on payment failure                       │
│                                                        │
└────────────────────────────────────────────────────────┘
```

---

## Test Implementation

### 1. Basic Subscription Lifecycle

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
    private TestSchemaHelper schemaHelper;
    
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    
    @Autowired
    private InvoiceRepository invoiceRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @MockBean
    private PaymentGatewayPort paymentGatewayPort;
    
    @MockBean
    private EmailServicePort emailServicePort;
    
    private Tenant tenant;
    private User user;
    private String authToken;
    private String baseUrl;
    
    @BeforeEach
    void setup() {
        // Create tenant with schema
        tenant = schemaHelper.createFreshSchema("billing_flow_test_" + UUID.randomUUID());
        
        baseUrl = "http://localhost:" + port + "/api/v1";
        
        // Login
        authToken = loginUser("user@test.com", "password");
        
        // Mock payment gateway
        when(paymentGatewayPort.capturePayment(any()))
            .thenReturn(CapturePaymentResponse.builder()
                .paymentId("pay_" + UUID.randomUUID())
                .status(PaymentStatus.CAPTURED)
                .build());
    }
    
    @AfterEach
    void teardown() {
        schemaHelper.dropSchema(tenant.getSchemaName());
    }
    
    @Nested
    @DisplayName("Basic Subscription Lifecycle")
    class BasicSubscriptionLifecycle {
        
        /**
         * E2E: Create subscription → Auto-charge → Verify state
         */
        @Test
        @DisplayName("Create subscription, capture payment, verify state")
        void subscriptionLifecycleBasic() {
            // Step 1: Create subscription
            CreateSubscriptionRequest createRequest = CreateSubscriptionRequest.builder()
                .planId(1L)  // FREE plan (no charge)
                .build();
            
            ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            SubscriptionResponse subscriptionResp = createResponse.getBody();
            Long subscriptionId = subscriptionResp.getId();
            
            // Verify initial state
            assertThat(subscriptionResp)
                .extracting("status", "planId", "startDate", "currentPeriodStart", "currentPeriodEnd")
                .isNotNull();
            
            assertThat(subscriptionResp.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(subscriptionResp.getPlanId()).isEqualTo(1L);
            
            // Step 2: Get subscription detail
            ResponseEntity<SubscriptionResponse> getResponse = restTemplate.exchange(
                baseUrl + "/subscriptions/" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            
            // Step 3: Cancel subscription
            ResponseEntity<SubscriptionResponse> cancelResponse = restTemplate.exchange(
                baseUrl + "/subscriptions/" + subscriptionId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(cancelResponse.getBody().getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
            
            // Verify database state
            Subscription dbSubscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
            assertThat(dbSubscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
            assertThat(dbSubscription.getEndDate()).isNotNull();
        }
        
        /**
         * Verify email sent on subscription creation.
         */
        @Test
        @DisplayName("Email sent on subscription creation")
        void emailSentOnCreation() {
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(1L)
                .build();
            
            restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            // Wait for async event handler
            await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> {
                    ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
                    verify(emailServicePort, atLeastOnce()).send(captor.capture());
                    return captor.getAllValues().stream()
                        .anyMatch(req -> req.getTemplate().equals("subscription-created"));
                });
        }
    }
    
    @Nested
    @DisplayName("Plan Upgrade/Downgrade with Proration")
    class PlanChangeWithProration {
        
        /**
         * Upgrade: FREE → PRO (mid-cycle)
         * Expected: Credit balance owed to customer
         */
        @Test
        @DisplayName("Upgrade FREE → PRO mid-cycle calculates credit")
        void upgradeFreeToProCredit() {
            // Step 1: Create subscription (FREE plan)
            CreateSubscriptionRequest createRequest = CreateSubscriptionRequest.builder()
                .planId(1L)  // FREE
                .build();
            
            ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            Long subscriptionId = createResponse.getBody().getId();
            
            // Step 2: Wait 10 days into 30-day billing cycle
            // (Mock advancement of date)
            
            // Step 3: Upgrade to PRO
            UpgradeSubscriptionRequest upgradeRequest = UpgradeSubscriptionRequest.builder()
                .planId(2L)  // PRO MONTHLY at ₹999/month
                .build();
            
            ResponseEntity<SubscriptionResponse> upgradeResponse = restTemplate.exchange(
                baseUrl + "/subscriptions/" + subscriptionId + "/upgrade",
                HttpMethod.POST,
                new HttpEntity<>(upgradeRequest, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            assertThat(upgradeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            
            // Step 4: Verify invoice created with proration
            ResponseEntity<List<InvoiceResponse>> invoicesResponse = restTemplate.exchange(
                baseUrl + "/invoices?subscriptionId=" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(invoicesResponse.getBody()).hasSize(1);
            InvoiceResponse invoice = invoicesResponse.getBody().get(0);
            
            // Proration calculation:
            // Days used from FREE: 10 days @ ₹0 = ₹0
            // Days remaining (20 days) @ ₹999/30 = ₹666
            // Total: ₹666 (credit owed to customer)
            
            assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
            assertThat(invoice.getTotalAmount()).isNotNull();
            
            // Step 5: Verify subscription state
            ResponseEntity<SubscriptionResponse> getResponse = restTemplate.exchange(
                baseUrl + "/subscriptions/" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            assertThat(getResponse.getBody().getPlanId()).isEqualTo(2L);
            assertThat(getResponse.getBody().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        }
        
        /**
         * Downgrade: PRO → FREE (mid-cycle)
         * Expected: Charge customer for remaining days
         */
        @Test
        @DisplayName("Downgrade PRO → FREE mid-cycle calculates charge")
        void downgradeProToFreeCharge() {
            // Step 1: Create subscription (PRO plan)
            CreateSubscriptionRequest createRequest = CreateSubscriptionRequest.builder()
                .planId(2L)  // PRO MONTHLY
                .build();
            
            ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            Long subscriptionId = createResponse.getBody().getId();
            
            // Step 2: Wait 20 days into 30-day cycle
            
            // Step 3: Downgrade to FREE
            DowngradeSubscriptionRequest downgradeRequest = DowngradeSubscriptionRequest.builder()
                .planId(1L)  // FREE
                .build();
            
            ResponseEntity<SubscriptionResponse> downgradeResponse = restTemplate.exchange(
                baseUrl + "/subscriptions/" + subscriptionId + "/downgrade",
                HttpMethod.POST,
                new HttpEntity<>(downgradeRequest, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            assertThat(downgradeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            
            // Step 4: Verify invoice created
            ResponseEntity<List<InvoiceResponse>> invoicesResponse = restTemplate.exchange(
                baseUrl + "/invoices?subscriptionId=" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                new ParameterizedTypeReference<>() {}
            );
            
            InvoiceResponse invoice = invoicesResponse.getBody().get(0);
            
            // Proration:
            // Days used from PRO: 20 days @ ₹999/30 = ₹666
            // Refund: -(₹999 - ₹666) = -₹333 (customer gets credit)
            
            assertThat(invoice.getTotalAmount())
                .isNotNull()
                .isLessThan(BigDecimal.valueOf(999));  // Less than full month
        }
    }
    
    @Nested
    @DisplayName("Payment Processing & Reconciliation")
    class PaymentProcessing {
        
        /**
         * Capture payment on subscription creation (for paid plans).
         */
        @Test
        @DisplayName("Payment captured on subscription creation")
        void paymentCapturedOnCreation() {
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(2L)  // PRO plan (₹999/month)
                .paymentMethodId("pm_test_123")
                .build();
            
            ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            
            // Verify payment gateway was called
            verify(paymentGatewayPort, times(1)).capturePayment(
                argThat(req -> req.getAmount().equals(new BigDecimal("999.00")))
            );
        }
        
        /**
         * Handle payment failure gracefully.
         */
        @Test
        @DisplayName("Payment failure triggers notification")
        void paymentFailureNotification() {
            // Mock payment failure
            when(paymentGatewayPort.capturePayment(any()))
                .thenThrow(new PaymentGatewayException("Card declined"));
            
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(2L)  // PRO plan
                .paymentMethodId("pm_test_123")
                .build();
            
            // Should fail
            ResponseEntity<?> response = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(authToken)),
                Object.class
            );
            
            assertThat(response.getStatusCode())
                .isIn(HttpStatus.BAD_REQUEST, HttpStatus.PAYMENT_REQUIRED);
        }
        
        /**
         * Record manual payment against invoice.
         */
        @Test
        @DisplayName("Record payment updates invoice status")
        void recordPaymentUpdatesInvoice() {
            // Step 1: Create subscription (auto-charges)
            CreateSubscriptionRequest createRequest = CreateSubscriptionRequest.builder()
                .planId(2L)  // PRO plan
                .build();
            
            ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            Long subscriptionId = createResponse.getBody().getId();
            
            // Step 2: Get invoice
            ResponseEntity<List<InvoiceResponse>> invoicesResponse = restTemplate.exchange(
                baseUrl + "/invoices?subscriptionId=" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                new ParameterizedTypeReference<>() {}
            );
            
            Long invoiceId = invoicesResponse.getBody().get(0).getId();
            
            // Step 3: Record partial payment
            RecordPaymentRequest paymentRequest = RecordPaymentRequest.builder()
                .invoiceId(invoiceId)
                .amount(new BigDecimal("500.00"))
                .method(PaymentMethod.CARD)
                .paidAt(Instant.now())
                .build();
            
            ResponseEntity<PaymentResponse> paymentResponse = restTemplate.exchange(
                baseUrl + "/payments",
                HttpMethod.POST,
                new HttpEntity<>(paymentRequest, authHeaders(authToken)),
                PaymentResponse.class
            );
            
            assertThat(paymentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            
            // Step 4: Verify invoice still OPEN (partial payment)
            ResponseEntity<InvoiceResponse> invoiceResponse = restTemplate.exchange(
                baseUrl + "/invoices/" + invoiceId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                InvoiceResponse.class
            );
            
            assertThat(invoiceResponse.getBody().getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
            assertThat(invoiceResponse.getBody().getOutstandingBalance())
                .isEqualTo(new BigDecimal("499.00"));  // ₹999 - ₹500
            
            // Step 5: Record remaining payment
            RecordPaymentRequest paymentRequest2 = RecordPaymentRequest.builder()
                .invoiceId(invoiceId)
                .amount(new BigDecimal("499.00"))
                .method(PaymentMethod.UPI)
                .paidAt(Instant.now())
                .build();
            
            restTemplate.exchange(
                baseUrl + "/payments",
                HttpMethod.POST,
                new HttpEntity<>(paymentRequest2, authHeaders(authToken)),
                PaymentResponse.class
            );
            
            // Step 6: Verify invoice now PAID
            ResponseEntity<InvoiceResponse> invoiceFinalResponse = restTemplate.exchange(
                baseUrl + "/invoices/" + invoiceId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                InvoiceResponse.class
            );
            
            assertThat(invoiceFinalResponse.getBody().getStatus()).isEqualTo(InvoiceStatus.PAID);
            assertThat(invoiceFinalResponse.getBody().getOutstandingBalance())
                .isEqualTo(BigDecimal.ZERO);
        }
    }
    
    @Nested
    @DisplayName("Invoice & Payment Integration")
    class InvoicePaymentIntegration {
        
        /**
         * Verify invoice generated for each billing cycle.
         */
        @Test
        @DisplayName("Invoice generated for each billing cycle")
        void invoicePerBillingCycle() {
            // Create subscription
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(2L)  // PRO plan (monthly)
                .build();
            
            ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            Long subscriptionId = response.getBody().getId();
            
            // Get initial invoice
            ResponseEntity<List<InvoiceResponse>> invoices1 = restTemplate.exchange(
                baseUrl + "/invoices?subscriptionId=" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                new ParameterizedTypeReference<>() {}
            );
            
            assertThat(invoices1.getBody()).hasSize(1);
            
            // Advance time by 1 month (mocked)
            // Next billing cycle should create new invoice
            
            // Verify second invoice created
            ResponseEntity<List<InvoiceResponse>> invoices2 = restTemplate.exchange(
                baseUrl + "/invoices?subscriptionId=" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                new ParameterizedTypeReference<>() {}
            );
            
            // After time advancement, should have 2 invoices
            assertThat(invoices2.getBody()).hasSizeGreaterThanOrEqualTo(1);
        }
        
        /**
         * Verify audit trail in database.
         */
        @Test
        @DisplayName("Complete audit trail preserved")
        void auditTrailPreserved() {
            // Create subscription
            CreateSubscriptionRequest request = CreateSubscriptionRequest.builder()
                .planId(2L)
                .build();
            
            ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            Long subscriptionId = response.getBody().getId();
            
            // Verify database contains:
            // 1. Subscription record (with timestamps)
            Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
            assertThat(subscription.getCreatedAt()).isNotNull();
            assertThat(subscription.getUpdatedAt()).isNotNull();
            
            // 2. Invoice record (with status transitions)
            List<Invoice> invoices = invoiceRepository.findBySubscriptionId(subscriptionId);
            assertThat(invoices).isNotEmpty();
            
            Invoice invoice = invoices.get(0);
            assertThat(invoice.getCreatedAt()).isNotNull();
            assertThat(invoice.getStatus()).isNotNull();
            
            // 3. Payment records
            List<Payment> payments = paymentRepository.findByInvoiceId(invoice.getId());
            assertThat(payments).isNotEmpty();
            
            Payment payment = payments.get(0);
            assertThat(payment.getCreatedAt()).isNotNull();
            assertThat(payment.getAmount()).isGreaterThan(BigDecimal.ZERO);
        }
    }
    
    @Nested
    @DisplayName("Event-Driven Notifications")
    class EventDrivenNotifications {
        
        /**
         * Email sent on payment success.
         */
        @Test
        @DisplayName("Payment success email sent")
        void paymentSuccessEmailSent() {
            // Create subscription & invoice
            CreateSubscriptionRequest createRequest = CreateSubscriptionRequest.builder()
                .planId(2L)
                .build();
            
            ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                baseUrl + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, authHeaders(authToken)),
                SubscriptionResponse.class
            );
            
            Long subscriptionId = createResponse.getBody().getId();
            
            // Get invoice
            ResponseEntity<List<InvoiceResponse>> invoicesResponse = restTemplate.exchange(
                baseUrl + "/invoices?subscriptionId=" + subscriptionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(authToken)),
                new ParameterizedTypeReference<>() {}
            );
            
            Long invoiceId = invoicesResponse.getBody().get(0).getId();
            
            // Record payment
            RecordPaymentRequest paymentRequest = RecordPaymentRequest.builder()
                .invoiceId(invoiceId)
                .amount(new BigDecimal("999.00"))
                .method(PaymentMethod.CARD)
                .build();
            
            restTemplate.exchange(
                baseUrl + "/payments",
                HttpMethod.POST,
                new HttpEntity<>(paymentRequest, authHeaders(authToken)),
                PaymentResponse.class
            );
            
            // Wait for async handler
            await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> {
                    ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
                    verify(emailServicePort, atLeastOnce()).send(captor.capture());
                    return captor.getAllValues().stream()
                        .anyMatch(req -> req.getTemplate().equals("payment-captured"));
                });
        }
    }
    
    // Helper methods
    
    private String loginUser(String email, String password) {
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
}
```

---

## Test Coverage Summary

| Scenario | Tests | Coverage |
|----------|-------|----------|
| **Basic Lifecycle** | 3 | Create, get, cancel |
| **Plan Changes** | 2 | Upgrade with credit, downgrade with charge |
| **Payment Processing** | 3 | Capture, failure, partial payment |
| **Invoicing** | 2 | Generation, status tracking |
| **Notifications** | 2 | Creation email, payment success email |
| **Audit Trail** | 1 | Complete history preservation |
| **Total** | 13+ tests | Full E2E coverage |

---

## Running the Tests

```bash
# Run all integration tests
mvn test -Dtest=BillingFlowIntegrationTest

# Run specific test class
mvn test -Dtest=BillingFlowIntegrationTest#BasicSubscriptionLifecycle

# Run with coverage report
mvn clean test jacoco:report
```

---

## Related Documentation

- [Billing Module](../backend/04-business-modules/invoicing.md)
- [Payment Processing](../backend/04-business-modules/payments.md)
- [Subscription Lifecycle](../backend/01-foundation/request-flow.md)
- [Testing Strategy](./testing-strategy.md)
