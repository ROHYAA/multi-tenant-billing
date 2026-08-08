package com.mtbs.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // Auth errors
    AUTH_INVALID_CREDENTIALS("AUTH_1001", "Invalid credentials", HttpStatus.UNAUTHORIZED),
    AUTH_TOKEN_EXPIRED("AUTH_1002", "Token has expired", HttpStatus.UNAUTHORIZED),
    AUTH_ACCESS_DENIED("AUTH_1003", "Access denied", HttpStatus.FORBIDDEN),
    AUTH_ACCOUNT_LOCKED("AUTH_1004", "Account is locked", HttpStatus.FORBIDDEN),
    AUTH_ACCOUNT_DISABLED("AUTH_1005", "Account is disabled", HttpStatus.FORBIDDEN),
    AUTH_EMAIL_ALREADY_EXISTS("AUTH_1006", "Email already exists", HttpStatus.CONFLICT),
    AUTH_RESET_TOKEN_INVALID("AUTH_1007", "Password reset token is invalid", HttpStatus.BAD_REQUEST),
    AUTH_RESET_TOKEN_EXPIRED("AUTH_1008", "Password reset token has expired", HttpStatus.BAD_REQUEST),
    AUTH_TOO_MANY_REQUESTS("AUTH_1009", "Too many failed login attempts. Try again later.", HttpStatus.TOO_MANY_REQUESTS),

    // Tenant errors
    TENANT_NOT_FOUND("TNT_2001", "Tenant not found", HttpStatus.NOT_FOUND),
    TENANT_ALREADY_EXISTS("TNT_2002", "Tenant already exists", HttpStatus.CONFLICT),
    TENANT_SCHEMA_ERROR("TNT_2003", "Tenant schema error", HttpStatus.INTERNAL_SERVER_ERROR),
    TENANT_SUSPENDED("TNT_2004", "Tenant is suspended", HttpStatus.FORBIDDEN),
    TENANT_SLUG_ALREADY_EXISTS("TNT_2005", "Tenant slug already taken", HttpStatus.CONFLICT),
    TENANT_NOT_IN_ONBOARDING("TNT_2006", "Tenant is not in onboarding state", HttpStatus.BAD_REQUEST),
    ONBOARDING_STEP_OUT_OF_ORDER("TNT_2007", "Onboarding step must be completed in order", HttpStatus.BAD_REQUEST),

    // Token errors
    TOKEN_INVALID("TKN_3001", "Invalid token", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("TKN_3002", "Token has expired", HttpStatus.UNAUTHORIZED),
    TOKEN_REVOKED("TKN_3003", "Token has been revoked", HttpStatus.UNAUTHORIZED),

    // Resource errors
    RESOURCE_NOT_FOUND("RES_4001", "Resource not found", HttpStatus.NOT_FOUND),
    RESOURCE_ALREADY_EXISTS("RES_4002", "Resource already exists", HttpStatus.CONFLICT),
    RESOURCE_ACCESS_DENIED("RES_4003", "Access to resource denied", HttpStatus.FORBIDDEN),
    RESOURCE_INVALID("RES_4004", "Invalid resource", HttpStatus.BAD_REQUEST),
    PLAN_LIMIT_EXCEEDED("RES_4005", "Plan limit exceeded", HttpStatus.PAYMENT_REQUIRED),

    // Subscription errors
    SUBSCRIPTION_NOT_FOUND("SUB_7001", "No active subscription found", HttpStatus.NOT_FOUND),
    SUBSCRIPTION_UPGRADE_PENDING("SUB_7002", "An upgrade payment is already in progress. Complete or cancel it before initiating a new upgrade.", HttpStatus.CONFLICT),
    SUBSCRIPTION_INVALID_TRANSITION("SUB_7003", "This plan change is not allowed from your current subscription state.", HttpStatus.BAD_REQUEST),
    SUBSCRIPTION_ALREADY_CANCELLED("SUB_7004", "Subscription is already scheduled for cancellation.", HttpStatus.CONFLICT),
    SUBSCRIPTION_NOT_CANCELLABLE("SUB_7005", "Only ACTIVE or TRIALING subscriptions can be cancelled.", HttpStatus.BAD_REQUEST),
    SUBSCRIPTION_SAME_CYCLE("SUB_7006", "You are already on this billing cycle.", HttpStatus.BAD_REQUEST),
    SUBSCRIPTION_DOWNGRADE_NOT_APPLICABLE("SUB_7007", "Only PRO or ENTERPRISE subscriptions can be downgraded to FREE.", HttpStatus.BAD_REQUEST),

    // Payment errors
    PAYMENT_FAILED("PAY_5001", "Payment processing failed", HttpStatus.PAYMENT_REQUIRED),
    PAYMENT_ALREADY_PROCESSED("PAY_5002", "Payment has already been processed", HttpStatus.CONFLICT),
    INVALID_PAYMENT_METHOD("PAY_5003", "Invalid payment method", HttpStatus.BAD_REQUEST),
    RAZORPAY_ERROR("PAY_5004", "Razorpay API error", HttpStatus.BAD_GATEWAY),
    INVALID_PAYMENT_SIGNATURE("PAY_5005", "Payment signature verification failed", HttpStatus.BAD_REQUEST),
    ORDER_CREATION_FAILED("PAY_5006", "Failed to create payment order", HttpStatus.INTERNAL_SERVER_ERROR),

    // Validation errors
    VALIDATION_ERROR("VAL_6001", "Validation error", HttpStatus.BAD_REQUEST),
    INVALID_FORMAT("VAL_6002", "Invalid format", HttpStatus.BAD_REQUEST),
    MISSING_REQUIRED_FIELD("VAL_6003", "Missing required field", HttpStatus.BAD_REQUEST),

    // General
    INTERNAL_ERROR("GEN_9001", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
