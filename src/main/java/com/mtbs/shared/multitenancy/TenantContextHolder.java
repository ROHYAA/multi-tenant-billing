package com.mtbs.shared.multitenancy;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Null-safe facade over {@link TenantContext}.
 *
 * TenantContext holds two raw ThreadLocals and requires callers to manage
 * null checks manually. This class centralises all null-safety in one place
 * so service code stays clean.
 *
 * Use TenantContextHolder everywhere except in the low-level infrastructure
 * classes (JwtAuthenticationFilter, Quartz jobs) that must interact with the
 * raw TenantContext directly for clear() lifecycle management.
 *
 * THREAD SAFETY:
 *   - All reads are null-safe and return Optional or a sensible default.
 *   - set() calls delegate directly to TenantContext — no buffering.
 *   - isSet() lets callers guard logic that requires a tenant context.
 */
@UtilityClass
@Slf4j
public class TenantContextHolder {

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Returns the current tenant ID or null if not set.
     * Prefer {@link #requireTenantId()} when tenant context is mandatory.
     */
    public static Long getTenantId() {
        return TenantContext.getTenantId();
    }

    /**
     * Returns the current schema name or null if not set.
     * Prefer {@link #requireSchemaName()} when tenant context is mandatory.
     */
    public static String getSchemaName() {
        return TenantContext.getSchemaName();
    }

    /**
     * Returns the tenant ID, throwing {@link IllegalStateException} if not set.
     * Use in service methods that require an authenticated tenant context.
     */
    public static Long requireTenantId() {
        Long id = TenantContext.getTenantId();
        if (id == null) {
            throw new IllegalStateException(
                    "TenantContext not initialised — tenantId is null. " +
                    "Ensure JwtAuthenticationFilter has run or TenantContext.setTenantId() " +
                    "was called before this method.");
        }
        return id;
    }

    /**
     * Returns the schema name, throwing {@link IllegalStateException} if not set.
     */
    public static String requireSchemaName() {
        String schema = TenantContext.getSchemaName();
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException(
                    "TenantContext not initialised — schemaName is null or blank. " +
                    "Ensure JwtAuthenticationFilter has run or TenantContext.setCurrentSchema() " +
                    "was called before this method.");
        }
        return schema;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Sets both tenantId and schemaName atomically.
     * This is the preferred write path — always set both together.
     */
    public static void set(Long tenantId, String schemaName) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setCurrentSchema(schemaName);
        log.trace("TenantContext set: tenantId={}, schema={}", tenantId, schemaName);
    }

    /**
     * Clears both ThreadLocals. Delegates to TenantContext.clear().
     * Prefer calling TenantContext.clear() directly in finally blocks —
     * this method exists for completeness and testing.
     */
    public static void clear() {
        TenantContext.clear();
        log.trace("TenantContext cleared");
    }

    // ── Guard ────────────────────────────────────────────────────────────────

    /**
     * Returns true if both tenantId and schemaName are set.
     * Useful for conditional logic in shared components that may run
     * in both tenant-scoped and non-tenant-scoped contexts.
     */
    public static boolean isSet() {
        return TenantContext.getTenantId() != null
                && TenantContext.getSchemaName() != null
                && !TenantContext.getSchemaName().isBlank();
    }
}