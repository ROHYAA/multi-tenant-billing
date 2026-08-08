package com.mtbs.shared.multitenancy;

import lombok.extern.slf4j.Slf4j;

/**
 * Thread-local holder for tenant context information.
 * 
 * <p>This class stores the current tenant's identification and schema context
 * using {@link ThreadLocal}, enabling multi-tenant data isolation throughout
 * the application request lifecycle.</p>
 * 
 * <p><b>Usage Pattern:</b></p>
 * <ul>
 *   <li>Set context in filter/servlet on request start via {@link #setTenantId(Long)}
 *       and {@link #setCurrentSchema(String)}</li>
 *   <li>Access anywhere in the call chain via {@link #getTenantId()} or {@link #getSchemaName()}</li>
 *   <li>Clear on request completion via {@link #clear()} to prevent thread reuse issues</li>
 * </ul>
 * 
 * <p><b>Thread Safety:</b> This class is thread-safe. Each thread maintains its own
 * independent copy of the context variables. The context must be explicitly cleared
 * to avoid leaking tenant data to subsequent requests that reuse the same thread.</p>
 * 
 * @author Manoj Pandi
 * @since 1.0
 * @see TenantContextHolder
 */
@Slf4j
public class TenantContext {

    private TenantContext() {}

    private static final ThreadLocal<String> CURRENT_SCHEMA = new ThreadLocal<>();
    private static final ThreadLocal<Long>   CURRENT_TENANT = new ThreadLocal<>();

    /**
     * Sets the current tenant ID for this thread.
     * 
     * <p>This method stores the tenant identifier in thread-local storage, making it
     * accessible anywhere within the current request's execution context.</p>
     * 
     * @param tenantId the unique identifier of the tenant; must not be null
     * @throws IllegalArgumentException if tenantId is null
     * @see #getTenantId()
     * @see #hasTenant()
     */
    public static void setTenantId(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
        CURRENT_TENANT.set(tenantId);
        log.debug("TenantContext: set tenantId={}", tenantId);
    }

    /**
     * Sets the current schema name for this thread.
     * 
     * <p>The schema name corresponds to the tenant's dedicated database schema used
     * for routing SQL queries to the correct tenant partition.</p>
     * 
     * @param schema the database schema name; must not be null or blank
     * @throws IllegalArgumentException if schema is null or blank
     * @see #getSchemaName()
     */
    public static void setCurrentSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema cannot be null or blank");
        }
        CURRENT_SCHEMA.set(schema);
        log.debug("TenantContext: set schemaName={}", schema);
    }

    /**
     * Retrieves the current tenant ID for this thread.
     * 
     * @return the current tenant ID, or {@code null} if not set
     * @see #setTenantId(Long)
     * @see #hasTenant()
     */
    public static Long getTenantId() {
        return CURRENT_TENANT.get();
    }

    /**
     * Retrieves the current schema name for this thread.
     * 
     * @return the current schema name, or {@code null} if not set
     * @see #setCurrentSchema(String)
     */
    public static String getSchemaName() {
        return CURRENT_SCHEMA.get();
    }

    /**
     * Clears the tenant context for this thread.
     * 
     * <p>This method removes both the tenant ID and schema name from thread-local storage.
     * It should be called at the end of every request (typically in a filter's finally block)
     * to ensure clean state and prevent tenant context leakage to subsequent requests
     * that may reuse the same thread.</p>
     * 
     * @see #setTenantId(Long)
     * @see #setCurrentSchema(String)
     */
    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_SCHEMA.remove();
        log.debug("TenantContext: cleared");
    }

    /**
     * Checks whether a tenant ID has been set for the current thread.
     * 
     * <p>This method provides a convenient way to verify tenant context availability
     * before performing tenant-specific operations.</p>
     * 
     * @return {@code true} if tenant ID is present and non-null, {@code false} otherwise
     * @see #getTenantId()
     */
    public static boolean hasTenant() {
        return CURRENT_TENANT.get() != null;
    }
}