package com.mtbs.billing;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.billing.dto.subscription.UpgradePreviewResponse;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.service.ProrationService;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.service.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProrationService Tests")
class ProrationServiceTest {

    @Mock
    private PlanService planService;

    @InjectMocks
    private ProrationService prorationService;

    private Plan freePlan;
    private Plan proPlanMonthly;
    private Plan proPlanAnnual;
    private Plan enterprisePlan;

    @BeforeEach
    void setUp() {
        freePlan = Plan.builder()
                .name("FREE")
                .displayName("Free Plan")
                .priceMonthly(BigDecimal.ZERO)
                .priceAnnual(BigDecimal.ZERO)
                .currency("INR")
                .isActive(true)
                .build();
        setIdIfNeeded(freePlan, 1L);

        proPlanMonthly = Plan.builder()
                .name("PRO")
                .displayName("Pro Plan")
                .priceMonthly(new BigDecimal("999"))
                .priceAnnual(new BigDecimal("9999"))
                .currency("INR")
                .trialDays(14)
                .isActive(true)
                .build();
        setIdIfNeeded(proPlanMonthly, 2L);

        proPlanAnnual = Plan.builder()
                .name("PRO")
                .displayName("Pro Plan")
                .priceMonthly(new BigDecimal("999"))
                .priceAnnual(new BigDecimal("9999"))
                .currency("INR")
                .trialDays(14)
                .isActive(true)
                .build();
        setIdIfNeeded(proPlanAnnual, 2L);

        enterprisePlan = Plan.builder()
                .name("ENTERPRISE")
                .displayName("Enterprise Plan")
                .priceMonthly(new BigDecimal("4999"))
                .priceAnnual(new BigDecimal("49999"))
                .currency("INR")
                .trialDays(30)
                .isActive(true)
                .build();
        setIdIfNeeded(enterprisePlan, 3L);
    }

    private void setIdIfNeeded(Plan plan, Long id) {
        try {
            var idField = plan.getClass().getSuperclass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            if (idField.get(plan) == null) {
                idField.set(plan, id);
            }
        } catch (Exception e) {
            // Fallback - use reflection to set id directly
        }
    }

    private Subscription createSubscription(Plan plan, BillingCycle cycle, int daysRemaining) {
        Subscription sub = Subscription.builder()
                .planId(plan.getId())
                .status(SubscriptionStatus.ACTIVE)
                .billingCycle(cycle)
                .currentPeriodStart(Instant.now().minus(30 - daysRemaining, ChronoUnit.DAYS))
                .currentPeriodEnd(Instant.now().plus(daysRemaining, ChronoUnit.DAYS))
                .build();
        setSubscriptionId(sub, 1L);
        return sub;
    }

    private Subscription createTrialingSubscription(Plan plan, int trialDaysRemaining) {
        Subscription sub = Subscription.builder()
                .planId(plan.getId())
                .status(SubscriptionStatus.TRIALING)
                .billingCycle(BillingCycle.MONTHLY)
                .trialStart(Instant.now().minus(14 - trialDaysRemaining, ChronoUnit.DAYS))
                .trialEnd(Instant.now().plus(trialDaysRemaining, ChronoUnit.DAYS))
                .currentPeriodStart(Instant.now())
                .currentPeriodEnd(Instant.now().plus(trialDaysRemaining, ChronoUnit.DAYS))
                .build();
        setSubscriptionId(sub, 1L);
        return sub;
    }

    private void setSubscriptionId(Subscription sub, Long id) {
        try {
            var idField = sub.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(sub, id);
        } catch (Exception e) {
            // Fallback
        }
    }

    @Nested
    @DisplayName("calculateChargeAmount")
    class CalculateChargeAmountTests {

        @Test
        @DisplayName("FREE to PRO monthly returns full monthly price")
        void freeToProMonthly_returnsFullMonthlyPrice() {
            Subscription current = createSubscription(freePlan, BillingCycle.MONTHLY, 15);

            when(planService.getPlanById(current.getPlanId())).thenReturn(freePlan);
            when(planService.getPlanById(proPlanMonthly.getId())).thenReturn(proPlanMonthly);

            BigDecimal charge = prorationService.calculateChargeAmount(current, proPlanMonthly, BillingCycle.MONTHLY);

            assertEquals(new BigDecimal("999.00"), charge);
        }

        @Test
        @DisplayName("FREE to PRO annual returns full annual price")
        void freeToProAnnual_returnsFullAnnualPrice() {
            Subscription current = createSubscription(freePlan, BillingCycle.ANNUAL, 200);

            when(planService.getPlanById(current.getPlanId())).thenReturn(freePlan);
            when(planService.getPlanById(proPlanAnnual.getId())).thenReturn(proPlanAnnual);

            BigDecimal charge = prorationService.calculateChargeAmount(current, proPlanAnnual, BillingCycle.ANNUAL);

            assertEquals(new BigDecimal("9999.00"), charge);
        }

        @Test
        @DisplayName("TRIALING to PRO monthly returns full price (no credit)")
        void trialingToProMonthly_noCredit_returnsFullPrice() {
            Subscription current = createTrialingSubscription(freePlan, 10);

            when(planService.getPlanById(current.getPlanId())).thenReturn(freePlan);
            when(planService.getPlanById(proPlanMonthly.getId())).thenReturn(proPlanMonthly);

            BigDecimal charge = prorationService.calculateChargeAmount(current, proPlanMonthly, BillingCycle.MONTHLY);

            assertEquals(new BigDecimal("999.00"), charge);
        }

        @Test
        @DisplayName("PRO to ENTERPRISE mid-cycle applies proration credit")
        void proToEnterprise_midCycle_appliesProrationCredit() {
            Subscription current = createSubscription(proPlanMonthly, BillingCycle.MONTHLY, 15);

            when(planService.getPlanById(current.getPlanId())).thenReturn(proPlanMonthly);
            when(planService.getPlanById(enterprisePlan.getId())).thenReturn(enterprisePlan);

            BigDecimal charge = prorationService.calculateChargeAmount(current, enterprisePlan, BillingCycle.MONTHLY);

            assertTrue(charge.compareTo(new BigDecimal("4999.00")) < 0);
            assertTrue(charge.compareTo(BigDecimal.ZERO) > 0);
        }

        @Test
        @DisplayName("PRO to ENTERPRISE last day has minimal credit but still charged")
        void proToEnterprise_lastDay_creditAlmostCoversCharge() {
            Subscription current = createSubscription(proPlanMonthly, BillingCycle.MONTHLY, 1);

            when(planService.getPlanById(current.getPlanId())).thenReturn(proPlanMonthly);
            when(planService.getPlanById(enterprisePlan.getId())).thenReturn(enterprisePlan);

            BigDecimal charge = prorationService.calculateChargeAmount(current, enterprisePlan, BillingCycle.MONTHLY);

            assertTrue(charge.compareTo(BigDecimal.ZERO) > 0);
        }

        @Test
        @DisplayName("Charge below ₹1 rounds up to Razorpay minimum")
        void chargeBelow1Rupee_roundsUpToRazorpayMinimum() {
            Subscription current = createSubscription(proPlanMonthly, BillingCycle.MONTHLY, 29);

            when(planService.getPlanById(current.getPlanId())).thenReturn(proPlanMonthly);
            when(planService.getPlanById(enterprisePlan.getId())).thenReturn(enterprisePlan);

            BigDecimal charge = prorationService.calculateChargeAmount(current, enterprisePlan, BillingCycle.MONTHLY);

            assertEquals(new BigDecimal("1.00"), charge);
        }

        @Test
        @DisplayName("Target FREE returns zero")
        void targetFree_returnsZero() {
            Subscription current = createSubscription(proPlanMonthly, BillingCycle.MONTHLY, 15);

            BigDecimal charge = prorationService.calculateChargeAmount(current, freePlan, BillingCycle.MONTHLY);

            assertEquals(BigDecimal.ZERO, charge);
        }
    }

    @Nested
    @DisplayName("toPaise")
    class ToPaiseTests {

        @Test
        @DisplayName("₹999 converts to 99900 paise")
        void toPaise_convertsCorrectly() {
            long paise = prorationService.toPaise(new BigDecimal("999"));

            assertEquals(99900L, paise);
        }

        @Test
        @DisplayName("Zero returns zero")
        void toPaise_zero_returnsZero() {
            long paise = prorationService.toPaise(BigDecimal.ZERO);

            assertEquals(0L, paise);
        }

        @Test
        @DisplayName("Null returns zero")
        void toPaise_null_returnsZero() {
            long paise = prorationService.toPaise(null);

            assertEquals(0L, paise);
        }
    }

    @Nested
    @DisplayName("buildPreview")
    class BuildPreviewTests {

        @Test
        @DisplayName("FREE to PRO no proration credit - creditAmount is null")
        void buildPreview_fromFree_noProrationCredit_creditAmountIsNull() {
            Subscription current = createSubscription(freePlan, BillingCycle.MONTHLY, 15);

            when(planService.getPlanById(current.getPlanId())).thenReturn(freePlan);
            when(planService.getPlanById(proPlanMonthly.getId())).thenReturn(proPlanMonthly);

            UpgradePreviewResponse preview = prorationService.buildPreview(current, proPlanMonthly.getId(), BillingCycle.MONTHLY);

            assertNull(preview.getCreditAmount());
            assertEquals(new BigDecimal("999.00"), preview.getChargeAmount());
            assertFalse(preview.isNoPaymentRequired());
        }

        @Test
        @DisplayName("PRO to ENTERPRISE has credit - creditAmount is positive")
        void buildPreview_fromPro_hasCredit_creditAmountIsPositive() {
            Subscription current = createSubscription(proPlanMonthly, BillingCycle.MONTHLY, 15);

            when(planService.getPlanById(current.getPlanId())).thenReturn(proPlanMonthly);
            when(planService.getPlanById(enterprisePlan.getId())).thenReturn(enterprisePlan);

            UpgradePreviewResponse preview = prorationService.buildPreview(current, enterprisePlan.getId(), BillingCycle.MONTHLY);

            assertNotNull(preview.getCreditAmount());
            assertTrue(preview.getCreditAmount().compareTo(BigDecimal.ZERO) > 0);
            assertEquals(15, preview.getRemainingDays());
            assertEquals(30, preview.getTotalPeriodDays());
        }

        @Test
        @DisplayName("Same plan throws ResourceException")
        void buildPreview_targetSamePlan_throwsResourceException() {
            Subscription current = createSubscription(proPlanMonthly, BillingCycle.MONTHLY, 15);

            when(planService.getPlanById(current.getPlanId())).thenReturn(proPlanMonthly);
            when(planService.getPlanById(proPlanMonthly.getId())).thenReturn(proPlanMonthly);

            ResourceException exception = assertThrows(ResourceException.class, 
                () -> prorationService.buildPreview(current, proPlanMonthly.getId(), BillingCycle.MONTHLY));

            assertTrue(exception.getMessage().contains("already on the"));
        }

        @Test
        @DisplayName("Inactive plan throws ResourceException")
        void buildPreview_inactivePlan_throwsResourceException() {
            Plan inactivePlan = Plan.builder()
                    .name("INACTIVE")
                    .displayName("Inactive Plan")
                    .isActive(false)
                    .build();

            Subscription current = createSubscription(freePlan, BillingCycle.MONTHLY, 15);

            when(planService.getPlanById(current.getPlanId())).thenReturn(freePlan);
            when(planService.getPlanById(inactivePlan.getId())).thenReturn(inactivePlan);

            ResourceException exception = assertThrows(ResourceException.class,
                () -> prorationService.buildPreview(current, inactivePlan.getId(), BillingCycle.MONTHLY));

            assertTrue(exception.getMessage().contains("not available"));
        }
    }

    @Nested
    @DisplayName("remainingDays")
    class RemainingDaysTests {

        @Test
        @DisplayName("Returns correct days when period end is in future")
        void remainingDays_futurePeriod_returnsCorrectDays() {
            Subscription sub = Subscription.builder()
                    .currentPeriodEnd(Instant.now().plus(15, ChronoUnit.DAYS))
                    .build();

            int days = prorationService.remainingDays(sub);

            assertEquals(15, days);
        }

        @Test
        @DisplayName("Returns zero when period end is in past")
        void remainingDays_pastPeriod_returnsZero() {
            Subscription sub = Subscription.builder()
                    .currentPeriodEnd(Instant.now().minus(5, ChronoUnit.DAYS))
                    .build();

            int days = prorationService.remainingDays(sub);

            assertEquals(0, days);
        }

        @Test
        @DisplayName("Returns zero when period end is null")
        void remainingDays_nullPeriodEnd_returnsZero() {
            Subscription sub = Subscription.builder()
                    .currentPeriodEnd(null)
                    .build();

            int days = prorationService.remainingDays(sub);

            assertEquals(0, days);
        }
    }
}