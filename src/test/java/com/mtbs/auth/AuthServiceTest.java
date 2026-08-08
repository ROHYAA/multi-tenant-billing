package com.mtbs.auth;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.auth.dto.auth.AuthResponse;
import com.mtbs.auth.dto.auth.LoginRequest;
import com.mtbs.auth.dto.auth.LogoutRequest;
import com.mtbs.auth.dto.auth.RefreshTokenRequest;
import com.mtbs.auth.entity.User;
import com.mtbs.auth.service.AuthService;
import com.mtbs.auth.service.TenantAuthService;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.repository.TenantRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("AuthService Integration Tests")
class AuthServiceTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public TenantAuthService tenantAuthService() {
            TenantAuthService mock = mock(TenantAuthService.class);
            
            AuthResponse mockResponse = AuthResponse.builder()
                .accessToken("mock_access_token")
                .refreshToken("mock_refresh_token")
                .userId(1L)
                .email("test@test.com")
                .name("Test User")
                .role("OWNER")
                .build();
            
            when(mock.loginInTenantSchema(any(), any(), anyString(), anyString()))
                .thenReturn(mockResponse);
            when(mock.refreshInTenantSchema(any(), any()))
                .thenReturn(mockResponse);
            
            return mock;
        }
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private TenantRepository tenantRepository;

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

    private Tenant createTestTenant(Status status) {
        Tenant tenant = Tenant.builder()
                .name("Test Tenant")
                .schemaName(currentSchema)
                .ownerEmail("owner@test.com")
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
            Tenant tenant = createTestTenant(Status.ACTIVE);

            LoginRequest request = LoginRequest.builder()
                .tenantId(tenant.getId())
                .email("test@test.com")
                .password("password")
                .build();

            AuthResponse response = authService.login(request, "127.0.0.1", "Chrome");

            assertNotNull(response);
            assertNotNull(response.getAccessToken());
            assertNotNull(response.getRefreshToken());
            assertNotNull(response.getUserId());
        }

        @Test
        @DisplayName("login suspended tenant throws TenantException")
        void login_suspendedTenant_throwsTenantSuspended() {
            Tenant tenant = createTestTenant(Status.SUSPENDED);

            LoginRequest request = LoginRequest.builder()
                .tenantId(tenant.getId())
                .email("test@test.com")
                .password("password")
                .build();

            assertThrows(com.mtbs.shared.exception.TenantException.class, () ->
                authService.login(request, "127.0.0.1", "Chrome")
            );
        }

        @Test
        @DisplayName("login inactive tenant throws TenantException")
        void login_inactiveTenant_throwsTenantException() {
            Tenant tenant = createTestTenant(Status.INACTIVE);

            LoginRequest request = LoginRequest.builder()
                .tenantId(tenant.getId())
                .email("test@test.com")
                .password("password")
                .build();

            assertThrows(com.mtbs.shared.exception.TenantException.class, () ->
                authService.login(request, "127.0.0.1", "Chrome")
            );
        }

        @Test
        @DisplayName("login pending onboarding tenant allows login and returns onboarding flag")
        void login_pendingOnboardingTenant_allowsLogin_returnsOnboardingFlag() {
            Tenant tenant = createTestTenant(Status.PENDING_ONBOARDING);
            tenant.setOnboardingStep(1);
            tenantRepository.save(tenant);

            LoginRequest request = LoginRequest.builder()
                .tenantId(tenant.getId())
                .email("test@test.com")
                .password("password")
                .build();

            AuthResponse response = authService.login(request, "127.0.0.1", "Chrome");

            assertNotNull(response);
            assertNotNull(response.getOnboardingComplete());
            assertFalse(response.getOnboardingComplete());
            assertEquals(1, response.getOnboardingStep());
        }

        @Test
        @DisplayName("login tenant not found throws TenantException")
        void login_tenantNotFound_throwsTenantException() {
            LoginRequest request = LoginRequest.builder()
                .tenantId(99999L)
                .email("test@test.com")
                .password("password")
                .build();

            assertThrows(com.mtbs.shared.exception.TenantException.class, () ->
                authService.login(request, "127.0.0.1", "Chrome")
            );
        }
    }

    @Nested
    @DisplayName("refreshAccessToken")
    class RefreshTokenTests {

        @Test
        @DisplayName("refreshAccessToken valid token returns new access token")
        void refreshAccessToken_validToken_returnsNewAccessToken() {
            Tenant tenant = createTestTenant(Status.ACTIVE);

            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantId(tenant.getId())
                .refreshToken("valid_refresh_token")
                .build();

            AuthResponse response = authService.refreshAccessToken(request);

            assertNotNull(response);
            assertNotNull(response.getAccessToken());
        }

        @Test
        @DisplayName("refreshAccessToken suspended tenant throws TenantException")
        void refreshAccessToken_suspendedTenant_throwsTenantException() {
            Tenant tenant = createTestTenant(Status.SUSPENDED);

            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantId(tenant.getId())
                .refreshToken("refresh_token")
                .build();

            assertThrows(com.mtbs.shared.exception.TenantException.class, () ->
                authService.refreshAccessToken(request)
            );
        }

        @Test
        @DisplayName("refreshAccessToken inactive tenant throws TenantException")
        void refreshAccessToken_inactiveTenant_throwsTenantException() {
            Tenant tenant = createTestTenant(Status.INACTIVE);

            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantId(tenant.getId())
                .refreshToken("refresh_token")
                .build();

            assertThrows(com.mtbs.shared.exception.TenantException.class, () ->
                authService.refreshAccessToken(request)
            );
        }

        @Test
        @DisplayName("refreshAccessToken tenant not found throws TenantException")
        void refreshAccessToken_tenantNotFound_throwsTenantException() {
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                .tenantId(99999L)
                .refreshToken("refresh_token")
                .build();

            assertThrows(com.mtbs.shared.exception.TenantException.class, () ->
                authService.refreshAccessToken(request)
            );
        }
    }

    @Nested
    @DisplayName("logout")
    class LogoutTests {

        @Test
        @DisplayName("logout revokes refresh token")
        void logout_revokesRefreshToken() {
            Tenant tenant = createTestTenant(Status.ACTIVE);

            LogoutRequest request = LogoutRequest.builder()
                .refreshToken("refresh_token_to_revoke")
                .build();

            assertDoesNotThrow(() ->
                authService.logout(request, tenant.getId(), "127.0.0.1", "Chrome")
            );
        }

        @Test
        @DisplayName("logout tenant not found throws TenantException")
        void logout_tenantNotFound_throwsTenantException() {
            LogoutRequest request = LogoutRequest.builder()
                .refreshToken("refresh_token")
                .build();

            assertThrows(com.mtbs.shared.exception.TenantException.class, () ->
                authService.logout(request, 99999L, "127.0.0.1", "Chrome")
            );
        }
    }
}