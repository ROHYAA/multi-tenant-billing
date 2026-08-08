package com.mtbs.shared.multitenancy;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propagates TenantContext (tenantId + schemaName) from the submitting thread
 * to the worker thread when tasks are executed via {@code @Async},
 * {@code CompletableFuture}, or any Spring-managed {@code TaskExecutor}.
 *
 * PROBLEM this solves:
 *   ThreadLocals are per-thread. When {@code @Async} submits work to a thread
 *   pool, the worker thread has a blank TenantContext. Any Hibernate operation
 *   in the async method will route to the wrong schema (or public schema),
 *   causing cross-tenant data leakage or "table not found" errors.
 *
 * HOW IT WORKS:
 *   Spring's TaskExecutor calls {@link #decorate(Runnable)} before each task.
 *   We capture both ThreadLocal values from the calling thread at submission
 *   time, pass them into the worker thread via a wrapper Runnable, and clear
 *   them again after the task finishes — even if the task throws.
 *
 * WIRING:
 *   Registered in {@code AsyncConfig.java} via:
 *     executor.setTaskDecorator(new TenantAwareTaskDecorator());
 *
 * IMPORTANT:
 *   This decorator only works with Spring-managed executors configured in
 *   AsyncConfig. It does NOT apply to raw Java threads, ForkJoinPool, or
 *   virtual threads created outside Spring's executor.
 *
 * NOTIFICATION SERVICE IMPLICATION:
 *   NotificationService uses {@code @Async}. Without this decorator, all
 *   email sends run on threads with null TenantContext. This is safe for
 *   notifications (they don't do JPA queries) but will fail if any future
 *   async method touches a tenant-scoped repository.
 */
@Slf4j
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(Runnable runnable) {
        // Capture context from the CALLING (submitting) thread
        Long tenantId     = TenantContext.getTenantId();
        String schemaName = TenantContext.getSchemaName();

        Map<String, String> mdcContextMap = MDC.getCopyOfContextMap();

        return () -> {
            try {
                // Restore context on the WORKER thread
                if (tenantId != null) {
                    TenantContext.setTenantId(tenantId);
                }
                if (schemaName != null && !schemaName.isBlank()) {
                    TenantContext.setCurrentSchema(schemaName);
                }

                if (!mdcContextMap.isEmpty()) {
                    MDC.setContextMap(mdcContextMap);
                }

                log.trace("Async task started with tenantId={}, schema={}", tenantId, schemaName);
                runnable.run();

            } finally {
                // Always clear the worker thread after the task — thread pool reuse safety
                TenantContext.clear();
                MDC.clear();
                log.trace("Async task completed, TenantContext cleared");
            }
        };
    }
}