package com.mtbs.support;

import com.mtbs.auth.entity.Role;
import com.mtbs.auth.entity.User;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.repository.PlanRepository;
import com.mtbs.tenant.repository.TenantRepository;
import com.mtbs.auth.repository.RoleRepository;
import com.mtbs.auth.repository.UserRepository;
import com.mtbs.billing.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TestDataBuilder {

    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    public static final String DEFAULT_TEST_EMAIL = "test@example.com";
    public static final String DEFAULT_TEST_PASSWORD = "Test@123";

    public TenantBuilder tenant() {
        return new TenantBuilder(this);
    }

    public SubscriptionBuilder subscription() {
        return new SubscriptionBuilder(this);
    }

    public UserBuilder user() {
        return new UserBuilder(this);
    }

    public void flush() {
        tenantRepository.flush();
        userRepository.flush();
        subscriptionRepository.flush();
    }

    public Plan getFreePlan() {
        return planRepository.findByName("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan not found in database"));
    }

    public Plan getProPlan() {
        return planRepository.findByName("PRO")
                .orElseThrow(() -> new IllegalStateException("PRO plan not found in database"));
    }

    public Plan getEnterprisePlan() {
        return planRepository.findByName("ENTERPRISE")
                .orElseThrow(() -> new IllegalStateException("ENTERPRISE plan not found in database"));
    }

    public Role getOwnerRole() {
        return roleRepository.findByName("OWNER")
                .orElseThrow(() -> new IllegalStateException("OWNER role not found in database"));
    }

    public static class TenantBuilder {
        private final TestDataBuilder builder;
        private String name = "Test Tenant";
        private String schemaName = "test_" + UUID.randomUUID().toString().substring(0, 8);
        private String ownerEmail = DEFAULT_TEST_EMAIL;
        private Status status = Status.ACTIVE;

        TenantBuilder(TestDataBuilder builder) {
            this.builder = builder;
        }

        public TenantBuilder name(String name) {
            this.name = name;
            return this;
        }

        public TenantBuilder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public TenantBuilder ownerEmail(String email) {
            this.ownerEmail = email;
            return this;
        }

        public TenantBuilder status(Status status) {
            this.status = status;
            return this;
        }

        public Tenant build() {
            Tenant tenant = Tenant.builder()
                    .name(name)
                    .schemaName(schemaName)
                    .ownerEmail(ownerEmail)
                    .status(status)
                    .build();
            return builder.tenantRepository.save(tenant);
        }

        public Tenant buildAndSetContext() {
            Tenant tenant = build();
            TenantContext.setTenantId(tenant.getId());
            TenantContext.setCurrentSchema(tenant.getSchemaName());
            return tenant;
        }
    }

    public static class SubscriptionBuilder {
        private final TestDataBuilder builder;
        private Long planId;
        private SubscriptionStatus status = SubscriptionStatus.ACTIVE;
        private BillingCycle billingCycle = BillingCycle.MONTHLY;
        private Instant currentPeriodStart;
        private Instant currentPeriodEnd;

        SubscriptionBuilder(TestDataBuilder builder) {
            this.builder = builder;
            this.currentPeriodStart = Instant.now();
            this.currentPeriodEnd = Instant.now().plus(30, ChronoUnit.DAYS);
        }

        public SubscriptionBuilder planId(Long planId) {
            this.planId = planId;
            return this;
        }

        public SubscriptionBuilder plan(Plan plan) {
            this.planId = plan.getId();
            return this;
        }

        public SubscriptionBuilder status(SubscriptionStatus status) {
            this.status = status;
            return this;
        }

        public SubscriptionBuilder billingCycle(BillingCycle billingCycle) {
            this.billingCycle = billingCycle;
            if (billingCycle == BillingCycle.ANNUAL) {
                this.currentPeriodEnd = Instant.now().plus(365, ChronoUnit.DAYS);
            } else {
                this.currentPeriodEnd = Instant.now().plus(30, ChronoUnit.DAYS);
            }
            return this;
        }

        public SubscriptionBuilder currentPeriodStart(Instant start) {
            this.currentPeriodStart = start;
            return this;
        }

        public SubscriptionBuilder currentPeriodEnd(Instant end) {
            this.currentPeriodEnd = end;
            return this;
        }

        public SubscriptionBuilder daysRemaining(int days) {
            this.currentPeriodEnd = Instant.now().plus(days, ChronoUnit.DAYS);
            return this;
        }

        public Subscription build() {
            if (planId == null) {
                planId = builder.getFreePlan().getId();
            }
            Subscription sub = Subscription.builder()
                    .planId(planId)
                    .status(status)
                    .billingCycle(billingCycle)
                    .currentPeriodStart(currentPeriodStart)
                    .currentPeriodEnd(currentPeriodEnd)
                    .build();
            return builder.subscriptionRepository.save(sub);
        }
    }

    public static class UserBuilder {
        private final TestDataBuilder builder;
        private String email = DEFAULT_TEST_EMAIL;
        private String password = DEFAULT_TEST_PASSWORD;
        private String name = "Test User";
        private Role role;

        UserBuilder(TestDataBuilder builder) {
            this.builder = builder;
        }

        public UserBuilder email(String email) {
            this.email = email;
            return this;
        }

        public UserBuilder password(String password) {
            this.password = password;
            return this;
        }

        public UserBuilder name(String name) {
            this.name = name;
            return this;
        }

        public UserBuilder role(Role role) {
            this.role = role;
            return this;
        }

        public User build() {
            if (role == null) {
                role = builder.getOwnerRole();
            }
            User user = User.builder()
                    .email(email)
                    .password(password)
                    .name(name)
                    .role(role)
                    .status(Status.ACTIVE)
                    .build();
            return builder.userRepository.save(user);
        }
    }
}