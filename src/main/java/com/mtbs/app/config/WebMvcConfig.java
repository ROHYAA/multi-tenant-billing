package com.mtbs.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The plan-usage-limit interceptor that used to be registered here lived in
 * the archived legacy.saasbilling.tenant.interceptor package and was removed
 * along with the platform-billing module.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
}
