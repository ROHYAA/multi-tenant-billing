package com.mtbs.shared.exception;

public class TenantException extends BaseException {

    public TenantException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public TenantException(ErrorCode errorCode) {
        super(errorCode);
    }

    public static TenantException notFound(Long tenantId) {
        return new TenantException(ErrorCode.TENANT_NOT_FOUND, "Tenant ID: " + tenantId);
    }

    public static TenantException notFound(String schemaName) {
        return new TenantException(ErrorCode.TENANT_NOT_FOUND, "Schema: " + schemaName);
    }

    public static TenantException alreadyExists(String name) {
        return new TenantException(ErrorCode.TENANT_ALREADY_EXISTS, name);
    }

    public static TenantException schemaError(String schema, String reason) {
        return new TenantException(ErrorCode.TENANT_SCHEMA_ERROR, schema + " - " + reason);
    }

    public static TenantException suspended(Long tenantId) {
        return new TenantException(ErrorCode.TENANT_SUSPENDED, "Tenant ID: " + tenantId);
    }

    // Add to TenantException.java
    public static TenantException slugAlreadyTaken(String slug) {
        return new TenantException(ErrorCode.TENANT_SLUG_ALREADY_EXISTS, "slug: " + slug);
    }

    public static TenantException notInOnboarding(Long tenantId) {
        return new TenantException(ErrorCode.TENANT_NOT_IN_ONBOARDING, "tenantId: " + tenantId);
    }

    public static TenantException stepOutOfOrder(int expected, int received) {
        return new TenantException(ErrorCode.ONBOARDING_STEP_OUT_OF_ORDER,
                "expected step " + expected + ", got " + received);
    }
}
