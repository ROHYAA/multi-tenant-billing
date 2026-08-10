package com.mtbs.tenant.numbering;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.support.TestSchemaHelper;
import com.mtbs.tenant.numbering.enums.NumberSeriesType;
import com.mtbs.tenant.numbering.service.NumberSeriesService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@DisplayName("NumberSeriesService Integration Tests")
class NumberSeriesServiceTest {

    @Autowired
    private NumberSeriesService numberSeriesService;

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

    @Test
    @DisplayName("nextNumber uses the seeded default INV prefix with no FY segment")
    void nextNumber_defaultSeries_formatsWithPrefixOnly() {
        String number = numberSeriesService.nextNumber(NumberSeriesType.INVOICE);
        assertEquals("INV-0001", number);
    }

    @Test
    @DisplayName("nextNumber increments monotonically across calls")
    void nextNumber_multipleCalls_incrementsSequentially() {
        String first  = numberSeriesService.nextNumber(NumberSeriesType.INVOICE);
        String second = numberSeriesService.nextNumber(NumberSeriesType.INVOICE);
        String third  = numberSeriesService.nextNumber(NumberSeriesType.INVOICE);

        assertEquals("INV-0001", first);
        assertEquals("INV-0002", second);
        assertEquals("INV-0003", third);
    }

    @Test
    @DisplayName("nextNumber never issues a duplicate under concurrent callers")
    void nextNumber_concurrentCalls_neverDuplicates() throws Exception {
        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            // Each thread needs its own TenantContext — it's a ThreadLocal.
            String schema = currentSchema;
            java.util.List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    TenantContext.setTenantId(1L);
                    TenantContext.setCurrentSchema(schema);
                    try {
                        return numberSeriesService.nextNumber(NumberSeriesType.INVOICE);
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }

            Set<String> results = new HashSet<>();
            for (Future<String> f : futures) {
                results.add(f.get());
            }

            assertEquals(callers, results.size(), "Every concurrently-issued number must be unique");
        } finally {
            pool.shutdown();
        }
    }
}
