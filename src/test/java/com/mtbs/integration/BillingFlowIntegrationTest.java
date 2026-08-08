package com.mtbs.integration;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.billing.dto.subscription.SubscriptionOrderResponse;
import com.mtbs.billing.dto.subscription.SubscriptionResponse;
import com.mtbs.billing.dto.subscription.UpgradePreviewResponse;
import com.mtbs.billing.dto.subscription.UpgradeRequest;
import com.mtbs.billing.dto.subscription.DowngradeRequest;
import com.mtbs.billing.dto.subscription.CycleChangeRequest;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.gateway.PaymentGatewayPort;
import com.mtbs.billing.repository.SubscriptionRepository;
import com.mtbs.billing.service.SubscriptionService;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestDataBuilder;
import com.mtbs.support.TestSchemaHelper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Billing Flow Integration Tests")
class BillingFlowIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public PaymentGatewayPort paymentGatewayPort() {
            PaymentGatewayPort mock = mock(PaymentGatewayPort.class);
            when(mock.createOrder(anyLong(), anyString(), anyString()))
                .thenReturn(com.mtbs.billing.dto.OrderResponse.builder()
                    .orderId("order_test_" + System.currentTimeMillis())
                    .amount(99900L)
                    .currency("INR")
                    .build());
            when(mock.verifyPaymentSignature(anyString(), anyString(), anyString()))
                .thenReturn(true);
            return mock;
        }
    }

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

    @Autowired
    private TestDataBuilder testDataBuilder;

    private String currentSchema;

    @BeforeEach
    void setUp() {
        currentSchema = testSchemaHelper.createFreshSchema();
        TenantContext.setTenantId(1L);
        TenantContext.setCurrentSchema(currentSchema);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        testSchemaHelper.dropSchema(currentSchema);
    }

    @Nested
    @DisplayName("Full upgrade flow - FREE to PRO MONTHLY")
    class FullUpgradeFlowTests {

        @Test
        @DisplayName("fullUpgradeFlow freeToProMonthly")
        void fullUpgradeFlow_freeToProMonthly() {
            // Step 1: Auto-subscribe to FREE
            SubscriptionResponse subResponse = subscriptionService.autoSubscribeToFreePlan();
            
            assertNotNull(subResponse);
            assertEquals(SubscriptionStatus.ACTIVE, subResponse.getStatus());
            assertEquals("FREE", subResponse.getPlanName());
            
            // Step 2: Get upgrade preview
            UpgradePreviewResponse preview = subscriptionService.previewUpgrade(
                testDataBuilder.getProPlan().getId(),
                BillingCycle.MONTHLY
            );
            
            assertNotNull(preview);
            assertEquals(testDataBuilder.getProPlan().getId(), preview.getTargetPlanId());
            assertNotNull(preview.getChargeAmount());
            
            // Step 3: Initiate upgrade
            UpgradeRequest upgradeRequest = UpgradeRequest.builder()
                .billingCycle(BillingCycle.MONTHLY)
                .build();
            
            SubscriptionOrderResponse orderResponse = subscriptionService.initiateUpgradeToPro(upgradeRequest);
            
            assertNotNull(orderResponse);
            assertNotNull(orderResponse.getRazorpayOrderId());
            assertNotNull(orderResponse.getChargeAmountPaise());
            
            // Verify subscription still shows FREE (not changed yet)
            Subscription sub = subscriptionRepository.findFirstByStatusIn(
                java.util.List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING)
            ).orElseThrow();
            
            assertEquals(testDataBuilder.getFreePlan().getId(), sub.getPlanId());
            assertNotNull(sub.getUpgradePendingInvoiceId());
            assertNotNull(sub.getUpgradePendingPlanId());
            
            // Step 4: Simulate payment verification - activate upgrade
            subscriptionService.activateUpgradeAfterPayment(orderResponse.getInvoiceId());
            
            // Verify subscription is now PRO
            Subscription upgradedSub = subscriptionRepository.findFirstByStatusIn(
                java.util.List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING)
            ).orElseThrow();
            
            assertEquals(testDataBuilder.getProPlan().getId(), upgradedSub.getPlanId());
            assertNull(upgradedSub.getUpgradePendingInvoiceId());
            assertNull(upgradedSub.getUpgradePendingPlanId());
            assertNull(upgradedSub.getUpgradePendingRazorpayOrderId());
        }
    }

    @Nested
    @DisplayName("Downgrade flow - PRO to FREE at period end")
    class DowngradeFlowTests {

        @Test
        @DisplayName("downgradeFlow proToFree atPeriodEnd")
        void downgradeFlow_proToFree_atPeriodEnd() {
            // Create PRO subscription
            Subscription sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .billingCycle(BillingCycle.MONTHLY)
                .build();
            
            // Step 1: Initiate downgrade at period end
            DowngradeRequest request = DowngradeRequest.builder()
                .atPeriodEnd(true)
                .reason("Testing downgrade")
                .build();
            
            SubscriptionResponse response = subscriptionService.downgradeToFree(request);
            
            // Verify plan still PRO (not changed yet)
            assertEquals(testDataBuilder.getProPlan().getId(), response.getPlanId());
            assertEquals("Free", response.getScheduledDowngradePlan());
            
            // Verify in DB
            Subscription dbSub = subscriptionRepository.findById(sub.getId()).orElseThrow();
            assertEquals(testDataBuilder.getFreePlan().getId(), dbSub.getScheduledDowngradePlanId());
            
            // Step 2: Execute scheduled downgrade
            subscriptionService.executeScheduledDowngrade(sub.getId());
            
            // Verify plan is now FREE
            Subscription downgradedSub = subscriptionRepository.findById(sub.getId()).orElseThrow();
            assertEquals(testDataBuilder.getFreePlan().getId(), downgradedSub.getPlanId());
            assertNull(downgradedSub.getScheduledDowngradePlanId());
        }

        @Test
        @DisplayName("downgradeFlow immediate")
        void downgradeFlow_immediate() {
            // Create PRO subscription
            Subscription sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .build();
            
            // Immediate downgrade
            DowngradeRequest request = DowngradeRequest.builder()
                .atPeriodEnd(false)
                .build();
            
            SubscriptionResponse response = subscriptionService.downgradeToFree(request);
            
            // Verify plan changed immediately to FREE
            assertEquals(testDataBuilder.getFreePlan().getId(), response.getPlanId());
            assertNull(response.getScheduledDowngradePlan());
        }
    }

    @Nested
    @DisplayName("Billing cycle change flow")
    class CycleChangeFlowTests {

        @Test
        @DisplayName("cycleChangeFlow monthlyToAnnual requires payment")
        void cycleChangeFlow_monthlyToAnnual_requiresPayment() {
            // Create MONTHLY PRO subscription
            Subscription sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .billingCycle(BillingCycle.MONTHLY)
                .build();
            
            // Step 1: Request cycle change to ANNUAL
            CycleChangeRequest request = CycleChangeRequest.builder()
                .newBillingCycle(BillingCycle.ANNUAL)
                .build();
            
            Object response = subscriptionService.changeBillingCycle(request);
            
            // Should return order response (payment required)
            assertTrue(response instanceof SubscriptionOrderResponse);
            SubscriptionOrderResponse orderResponse = (SubscriptionOrderResponse) response;
            assertNotNull(orderResponse.getRazorpayOrderId());
            assertTrue(orderResponse.getChargeAmountPaise() > 0);
            
            // Current cycle still MONTHLY until payment
            Subscription dbSub = subscriptionRepository.findById(sub.getId()).orElseThrow();
            assertEquals(BillingCycle.MONTHLY, dbSub.getBillingCycle());
        }

        @Test
        @DisplayName("cycleChangeFlow annualToMonthly no payment")
        void cycleChangeFlow_annualToMonthly_noPayment() {
            // Create ANNUAL PRO subscription
            Subscription sub = testDataBuilder.subscription()
                .plan(testDataBuilder.getProPlan())
                .billingCycle(BillingCycle.ANNUAL)
                .build();
            
            // Request cycle change to MONTHLY (downgrade - no payment)
            CycleChangeRequest request = CycleChangeRequest.builder()
                .newBillingCycle(BillingCycle.MONTHLY)
                .build();
            
            Object response = subscriptionService.changeBillingCycle(request);
            
            // Should return subscription response (no payment needed)
            assertTrue(response instanceof SubscriptionResponse);
            SubscriptionResponse subResponse = (SubscriptionResponse) response;
            
            // Current billing cycle still ANNUAL
            assertEquals(BillingCycle.ANNUAL, subResponse.getBillingCycle());
            // But scheduled to change to MONTHLY
            assertEquals(BillingCycle.MONTHLY, subResponse.getScheduledBillingCycle());
            
            // Step 2: Apply scheduled cycle change
            subscriptionService.applyScheduledCycleChange(sub.getId());
            
            // Verify cycle is now MONTHLY
            Subscription updatedSub = subscriptionRepository.findById(sub.getId()).orElseThrow();
            assertEquals(BillingCycle.MONTHLY, updatedSub.getBillingCycle());
            assertNull(updatedSub.getScheduledBillingCycle());
        }
    }
}