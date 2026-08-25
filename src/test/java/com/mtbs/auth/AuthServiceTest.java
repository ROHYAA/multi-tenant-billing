package com.mtbs.auth;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.auth.dto.auth.AuthResponse;
import com.mtbs.auth.dto.auth.LoginRequest;
import com.mtbs.auth.dto.auth.LogoutRequest;
import com.mtbs.auth.dto.auth.RefreshTokenRequest;
import com.mtbs.auth.dto.auth.TokenPair;
import com.mtbs.auth.service.AuthService;
import com.mtbs.auth.service.TenantAuthService;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.exception.TenantException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import com.mtbs.tenant.entity.Shop;
import com.mtbs.tenant.repository.ShopRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@Import(AuthServiceTest.TestConfig.class)
@DisplayName("AuthService Integration Tests")
class AuthServiceTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public TenantAuthService tenantAuthService() {
            TenantAuthService mock = mock(TenantAuthService.class);

            // loginInTenantSchema/refreshInTenantSchema populate the TokenPair
            // out-parameter by mutation (not via the return value) — the real
            // TenantAuthService does the same, so the mock must too, or
            // AuthService.login()'s "if (accessToken != null)" cookie branch
            // never fires.
            when(mock.loginInTenantSchema(any(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    TokenPair tokenPair = invocation.getArgument(4);
                    tokenPair.setAccessToken("mock_access_token");
                    tokenPair.setRefreshToken("mock_refresh_token");
                    return AuthResponse.forTenantUser(
                            900L, Instant.now(), 1L, "test@test.com", "OWNER",
                            List.of(), 1L, "Test Shop", false, false, false, Status.ACTIVE);
                });

            when(mock.refreshInTenantSchema(any(), any(), any()))
                .thenAnswer(invocation -> {
                    TokenPair tokenPair = invocation.getArgument(2);
                    tokenPair.setAccessToken("mock_new_access_token");
                    tokenPair.setRefreshToken("mock_new_refresh_token");
                    return AuthResponse.forTenantUser(
                            900L, Instant.now(), 1L, "test@test.com", "OWNER",
                            List.of(), 1L, "Test Shop", false, false, false, Status.ACTIVE);
                });

            return mock;
        }
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private ShopRepository tenantRepository;

    @Autowired
    private TestSchemaHelper testSchemaHelper;

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

    private Shop createTestTenant(Status status) {
        Shop tenant = Shop.builder()
                .name("Test Shop")
                .schemaName(currentSchema)
                .ownerEmail("owner@test.com")
                .slug("test-shop-" + UUID.randomUUID().toString().substring(0, 8))
                .status(status)
                .build();

        return tenantRepository.save(tenant);
    }

    @Nested
    @DisplayName("login")
    class LoginTests {

        @Test
        @DisplayName("login valid credentials returns JWT and refresh token")
        void login_validCredentials_returnsJwtAndRefreshToken() {
            Shop tenant = createTestTenant(Status.ACTIVE);

            LoginRequest request = LoginRequest.builder()
                .tenantSlug(tenant.getSlug())
                .email("test@test.com")
                .password("password")
                .build();

            AuthResponse response = authService.login(
                    request, "127.0.0.1", "Chrome", new MockHttpServletResponse());

            assertNotNull(response);
            assertNotNull(response.getUser());
            assertEquals(1L, response.getUser().getUserId());
            assertNotNull(response.getSession());
        }

        @Test
        @DisplayName("login suspended tenant throws TenantException")
        void login_suspendedTenant_throwsTenantSuspended() {
            Shop tenant = createTestTenant(Status.SUSPENDED);

            LoginRequest request = LoginRequest.builder()
                .tenantSlug(tenant.getSlug())
                .email("test@test.com")
                .password("password")
                .build();

            assertThrows(TenantException.class, () ->
                authService.login(request, "127.0.0.1", "Chrome", new MockHttpServletResponse())
            );
        }

        @Test
        @DisplayName("login inactive tenant throws TenantException")
        void login_inactiveTenant_throwsTenantException() {
            Shop tenant = createTestTenant(Status.INACTIVE);

            LoginRequest request = LoginRequest.builder()
                .tenantSlug(tenant.getSlug())
                .email("test@test.com")
                .password("password")
                .build();

            assertThrows(TenantException.class, () ->
                authService.login(request, "127.0.0.1", "Chrome", new MockHttpServletResponse())
            );
        }

        @Test
        @DisplayName("login tenant not found throws TenantException")
        void login_tenantNotFound_throwsTenantException() {
            LoginRequest request = LoginRequest.builder()
                .tenantSlug("no-such-shop-" + UUID.randomUUID())
                .email("test@test.com")
                .password("password")
                .build();

            assertThrows(TenantException.class, () ->
                authService.login(request, "127.0.0.1", "Chrome", new MockHttpServletResponse())
            );
        }
    }

    @Nested
    @DisplayName("refreshAccessToken")
    class RefreshTokenTests {

        @Test
        @DisplayName("refreshAccessToken valid token returns new access token")
        void refreshAccessToken_validToken_returnsNewAccessToken() {
            Shop tenant = createTestTenant(Status.ACTIVE);

            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantSlug(tenant.getSlug())
                .refreshToken("valid_refresh_token")
                .build();

            AuthResponse response = authService.refreshAccessToken(
                    request, new MockHttpServletRequest(), new MockHttpServletResponse());

            assertNotNull(response);
            assertNotNull(response.getUser());
        }

        @Test
        @DisplayName("refreshAccessToken suspended tenant throws TenantException")
        void refreshAccessToken_suspendedTenant_throwsTenantException() {
            Shop tenant = createTestTenant(Status.SUSPENDED);

            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantSlug(tenant.getSlug())
                .refreshToken("refresh_token")
                .build();

            assertThrows(TenantException.class, () ->
                authService.refreshAccessToken(
                        request, new MockHttpServletRequest(), new MockHttpServletResponse())
            );
        }

        @Test
        @DisplayName("refreshAccessToken inactive tenant throws TenantException")
        void refreshAccessToken_inactiveTenant_throwsTenantException() {
            Shop tenant = createTestTenant(Status.INACTIVE);

            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantSlug(tenant.getSlug())
                .refreshToken("refresh_token")
                .build();

            assertThrows(TenantException.class, () ->
                authService.refreshAccessToken(
                        request, new MockHttpServletRequest(), new MockHttpServletResponse())
            );
        }

        @Test
        @DisplayName("refreshAccessToken tenant not found throws TenantException")
        void refreshAccessToken_tenantNotFound_throwsTenantException() {
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantSlug("no-such-shop-" + UUID.randomUUID())
                .refreshToken("refresh_token")
                .build();

            assertThrows(TenantException.class, () ->
                authService.refreshAccessToken(
                        request, new MockHttpServletRequest(), new MockHttpServletResponse())
            );
        }
    }

    @Nested
    @DisplayName("logout")
    class LogoutTests {

        @Test
        @DisplayName("logout revokes refresh token")
        void logout_revokesRefreshToken() {
            Shop tenant = createTestTenant(Status.ACTIVE);

            LogoutRequest request = LogoutRequest.builder()
                .refreshToken("refresh_token_to_revoke")
                .build();

            assertDoesNotThrow(() ->
                authService.logout(
                        request, tenant.getId(), "127.0.0.1", "Chrome",
                        new MockHttpServletRequest(), new MockHttpServletResponse())
            );
        }

        @Test
        @DisplayName("logout tenant not found throws TenantException")
        void logout_tenantNotFound_throwsTenantException() {
            LogoutRequest request = LogoutRequest.builder()
                .refreshToken("refresh_token")
                .build();

            assertThrows(TenantException.class, () ->
                authService.logout(
                        request, 99999L, "127.0.0.1", "Chrome",
                        new MockHttpServletRequest(), new MockHttpServletResponse())
            );
        }
    }
}
