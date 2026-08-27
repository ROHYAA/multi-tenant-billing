package com.mtbs.tenant.config;

import com.mtbs.tenant.entity.Shop;
import com.mtbs.tenant.repository.ShopRepository;
import com.mtbs.tenant.service.TenantFlywayMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Re-applies tenant-schema Flyway migrations for every existing shop on
 * every app startup.
 *
 * Without this, TenantFlywayMigrationService.createSchemaAndMigrate() is
 * only ever called once — from SignupService, at the moment a shop is
 * created. Any tenant-schema migration added to the codebase AFTER a shop
 * already exists (e.g. V28__add_payment_status_and_group.sql) would
 * otherwise never reach that shop's schema — every request touching a
 * table/column that migration added (payments.status, in that case) would
 * fail with a "column does not exist" SQL error, breaking every feature
 * that reads or writes it (payment recording, PDF printing which reads
 * Payment rows, dashboard/report queries) for any shop created before the
 * migration existed.
 *
 * Flyway.migrate() is idempotent and only applies versions newer than a
 * schema's current flyway_schema_history — re-running it here against an
 * already-up-to-date schema is a fast no-op, so this is safe to run on
 * every boot regardless of how many migrations are actually new.
 *
 * One shop's migration failure is logged and does not stop the others —
 * a bad schema shouldn't take down startup for every other tenant.
 *
 * Disabled under the "test" profile — every test creates its own disposable
 * schema directly via TestSchemaHelper and never relies on this sweep, so
 * running it there would only add startup cost that grows with however many
 * shop rows past test runs have left behind in the shared test database.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class TenantMigrationRunner implements ApplicationRunner {

    private final ShopRepository shopRepository;
    private final TenantFlywayMigrationService tenantFlywayMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        List<Shop> shops = shopRepository.findAll();
        log.info("Re-applying tenant-schema migrations for {} existing shop(s)...", shops.size());

        int failed = 0;
        for (Shop shop : shops) {
            try {
                tenantFlywayMigrationService.createSchemaAndMigrate(shop.getSchemaName());
            } catch (Exception e) {
                failed++;
                log.error("Tenant migration failed for shopId={}, schema={} — this shop may be missing recent " +
                        "columns/tables until this is retried on a future startup: {}",
                        shop.getId(), shop.getSchemaName(), e.getMessage());
            }
        }

        log.info("Tenant-schema migration sweep complete — {} of {} shop(s) up to date.",
                shops.size() - failed, shops.size());
    }
}
