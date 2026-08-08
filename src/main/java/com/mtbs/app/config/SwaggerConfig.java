package com.mtbs.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

        @Bean
        public OpenAPI openAPI() {
                return new OpenAPI()
                                .info(new Info()
                                                .title("Multi-Tenant Billing System API")
                                                .version("1.0.0")
                                                .description("Enterprise-grade multi-tenant SaaS billing platform with "
                                                                +
                                                                "schema-per-tenant isolation, subscription management, usage metering, "
                                                                +
                                                                "invoice generation, Razorpay payment integration, and plan limit enforcement.")
                                                .contact(new Contact()
                                                                .name("MTBS Support")
                                                                .email("support@mtbs.com"))
                                                .license(new License()
                                                                .name("MIT License")
                                                                .url("https://opensource.org/licenses/MIT")))
                                .addSecurityItem(new SecurityRequirement().addList("Bearer JWT"))
                                .components(new Components()
                                                .addSecuritySchemes("Bearer JWT",
                                                                new SecurityScheme()
                                                                                .type(SecurityScheme.Type.HTTP)
                                                                                .scheme("bearer")
                                                                                .bearerFormat("JWT")
                                                                                .description("Enter JWT token")));
        }
}
