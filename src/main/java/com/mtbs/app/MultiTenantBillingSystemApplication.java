package com.mtbs.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.mtbs")
@EnableAsync
@EnableScheduling
public class MultiTenantBillingSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultiTenantBillingSystemApplication.class, args);
    }
}
