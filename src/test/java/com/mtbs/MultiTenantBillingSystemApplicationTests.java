package com.mtbs;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
class MultiTenantBillingSystemApplicationTests {

    @Test
    void contextLoads() {
    }

}
