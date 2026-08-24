package com.mtbs.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The plan-usage-limit interceptor that used to be registered here lived in
 * the archived legacy.saasbilling.tenant.interceptor package and was removed
 * along with the platform-billing module.
 *
 * SPA fallback for the bundled Angular frontend (see Dockerfile) lives in
 * GlobalExceptionHandler.handleNoResourceFound — Spring's static-resource
 * handler throws NoResourceFoundException for any unmatched path rather than
 * dispatching to a view-controller wildcard, so that's where this needs to
 * be handled rather than here.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
}
