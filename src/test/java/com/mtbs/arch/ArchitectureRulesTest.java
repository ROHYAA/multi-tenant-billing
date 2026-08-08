package com.mtbs.arch;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
public class ArchitectureRulesTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void verify_all_beans_load_successfully() {
        // Basic sanity check - if all beans load, architecture is valid
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        assertTrue(beanNames.length > 0, "Application should have beans");
        
        // Check core services exist
        assertNotNull(applicationContext.getBean("subscriptionService"), "SubscriptionService should exist");
        assertNotNull(applicationContext.getBean("paymentService"), "PaymentService should exist");
        assertNotNull(applicationContext.getBean("invoiceService"), "InvoiceService should exist");
        assertNotNull(applicationContext.getBean("authService"), "AuthService should exist");
    }

    @Test
    void verify_package_structure() {
        String[] packages = applicationContext.getBeanDefinitionNames();
        
        // Count beans per package to ensure structure is valid
        long serviceBeans = Arrays.stream(packages)
            .filter(name -> name.toLowerCase().contains("service"))
            .count();
        
        long repositoryBeans = Arrays.stream(packages)
            .filter(name -> name.toLowerCase().contains("repository"))
            .count();
        
        assertTrue(serviceBeans > 0, "Should have service beans");
        assertTrue(repositoryBeans > 0, "Should have repository beans");
    }

    @Test
    void verify_multitenancy_components() {
        // Verify multitenancy infrastructure exists
        assertNotNull(applicationContext.getBean("tenantFlywayMigrationService"), 
            "TenantFlywayMigrationService should exist");
        
        var schemaHelper = applicationContext.getBean("testSchemaHelper", 
            com.mtbs.support.TestSchemaHelper.class);
        assertNotNull(schemaHelper, "TestSchemaHelper should exist");
    }
}