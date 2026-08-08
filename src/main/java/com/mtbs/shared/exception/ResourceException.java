package com.mtbs.shared.exception;

public class ResourceException extends BaseException {

    public ResourceException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public ResourceException(ErrorCode errorCode) {
        super(errorCode);
    }

    public static ResourceException notFound(String resource, Long id) {
        return new ResourceException(ErrorCode.RESOURCE_NOT_FOUND, resource + " with ID: " + id);
    }

    public static ResourceException notFound(String resource, String identifier) {
        return new ResourceException(ErrorCode.RESOURCE_NOT_FOUND, resource + ": " + identifier);
    }

    public static ResourceException alreadyExists(String resource, String identifier) {
        return new ResourceException(ErrorCode.RESOURCE_ALREADY_EXISTS, resource + ": " + identifier);
    }

    public static ResourceException accessDenied() {
        return new ResourceException(ErrorCode.RESOURCE_ACCESS_DENIED);
    }

    public static ResourceException accessDenied(String detail) {
        return new ResourceException(ErrorCode.RESOURCE_ACCESS_DENIED, detail);
    }

    public static ResourceException invalid(String detail) {
        return new ResourceException(ErrorCode.RESOURCE_INVALID, detail);
    }

    public static ResourceException planLimitExceeded(String metric, long limit, long current) {
        return new ResourceException(ErrorCode.PLAN_LIMIT_EXCEEDED,
                String.format("%s - limit: %d, current: %d", metric, limit, current));
    }
}
