package com.mtbs.tenant.settings;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.shared.enums.settings.PaperSize;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import com.mtbs.tenant.settings.dto.ShopSettingsResponse;
import com.mtbs.tenant.settings.dto.UpdateShopSettingsRequest;
import com.mtbs.tenant.settings.service.ShopSettingsService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("ShopSettingsService Integration Tests")
class ShopSettingsServiceTest {

    @Autowired
    private ShopSettingsService shopSettingsService;

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

    @Nested
    @DisplayName("getSettings")
    class GetSettingsTests {

        @Test
        @DisplayName("getSettings on a freshly migrated schema returns the seeded defaults")
        void getSettings_freshSchema_returnsSeededDefaults() {
            ShopSettingsResponse response = shopSettingsService.getSettings();

            assertNotNull(response);
            assertEquals("INR", response.getCurrency());
            assertEquals("₹", response.getCurrencySymbol());
            assertEquals(2, response.getDecimalPrecision());
            assertEquals("Asia/Kolkata", response.getTimezone());
            assertEquals("en-IN", response.getLanguage());
            assertEquals(PaperSize.A4, response.getPaperSize());
            assertNotNull(response.getBillTemplateId());
            assertTrue(response.getShowLogo());
            assertTrue(response.getShowGst());
            assertFalse(response.getShowQrCode());
            assertEquals(5, response.getMargin());
            assertEquals(10, response.getFontSize());
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettingsTests {

        @Test
        @DisplayName("updateSettings applies only non-null fields")
        void updateSettings_partialUpdate_appliesOnlyProvidedFields() {
            UpdateShopSettingsRequest request = UpdateShopSettingsRequest.builder()
                .businessName("Rohit General Store")
                .showGst(false)
                .build();

            ShopSettingsResponse response = shopSettingsService.updateSettings(request);

            assertEquals("Rohit General Store", response.getBusinessName());
            assertFalse(response.getShowGst());
            // Untouched fields keep their seeded defaults
            assertEquals("INR", response.getCurrency());
            assertTrue(response.getShowLogo());
        }

        @Test
        @DisplayName("updateSettings mismatched thermalWidth for A4 paperSize throws")
        void updateSettings_thermalWidthMismatchForA4_throws() {
            UpdateShopSettingsRequest request = UpdateShopSettingsRequest.builder()
                .thermalWidth(80)
                .build();

            assertThrows(ResourceException.class, () ->
                shopSettingsService.updateSettings(request)
            );
        }

        @Test
        @DisplayName("updateSettings matching thermalWidth for thermal paperSize succeeds")
        void updateSettings_matchingThermalWidth_succeeds() {
            UpdateShopSettingsRequest request = UpdateShopSettingsRequest.builder()
                .paperSize(PaperSize.THERMAL_80MM)
                .thermalWidth(80)
                .build();

            ShopSettingsResponse response = shopSettingsService.updateSettings(request);

            assertEquals(PaperSize.THERMAL_80MM, response.getPaperSize());
            assertEquals(80, response.getThermalWidth());
        }

        @Test
        @DisplayName("updateSettings unknown billTemplateId throws")
        void updateSettings_unknownBillTemplateId_throws() {
            UpdateShopSettingsRequest request = UpdateShopSettingsRequest.builder()
                .billTemplateId(999999L)
                .build();

            assertThrows(ResourceException.class, () ->
                shopSettingsService.updateSettings(request)
            );
        }
    }
}
