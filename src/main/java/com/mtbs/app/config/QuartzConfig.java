package com.mtbs.app.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Quartz JDBC job-store wiring (see application.yaml) is kept dormant here —
 * every job that used to be registered (billing cycle, subscription
 * expiry/cancel, payment retry, trial expiry/ending-soon) lived in the
 * archived legacy.saasbilling.billing.scheduler.job package and has been
 * removed along with the platform-billing module.
 *
 * Register ShopLedger's own scheduled jobs (e.g. daily report email) here
 * as JobDetail/Trigger @Bean pairs, following the pattern the old billing
 * jobs used.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class QuartzConfig {
}
