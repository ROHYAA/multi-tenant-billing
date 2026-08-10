package com.mtbs.support;

import com.mtbs.auth.entity.Role;
import com.mtbs.auth.entity.User;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.entity.Shop;
import com.mtbs.tenant.repository.ShopRepository;
import com.mtbs.auth.repository.RoleRepository;
import com.mtbs.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TestDataBuilder {

    private final ShopRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public static final String DEFAULT_TEST_EMAIL = "test@example.com";
    public static final String DEFAULT_TEST_PASSWORD = "Test@123";

    public TenantBuilder tenant() {
        return new TenantBuilder(this);
    }

    public UserBuilder user() {
        return new UserBuilder(this);
    }

    public void flush() {
        tenantRepository.flush();
        userRepository.flush();
    }

    public Role getOwnerRole() {
        return roleRepository.findByName("OWNER")
                .orElseThrow(() -> new IllegalStateException("OWNER role not found in database"));
    }

    public static class TenantBuilder {
        private final TestDataBuilder builder;
        private String name = "Test Shop";
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

        public Shop build() {
            Shop tenant = Shop.builder()
                    .name(name)
                    .schemaName(schemaName)
                    .ownerEmail(ownerEmail)
                    .status(status)
                    .build();
            return builder.tenantRepository.save(tenant);
        }

        public Shop buildAndSetContext() {
            Shop tenant = build();
            TenantContext.setTenantId(tenant.getId());
            TenantContext.setCurrentSchema(tenant.getSchemaName());
            return tenant;
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
