package com.mtbs.shared.exception;

public class AuthException extends BaseException {

    public AuthException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public AuthException(ErrorCode errorCode) {
        super(errorCode);
    }

    public static AuthException invalidCredentials() {
        return new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    public static AuthException tokenExpired() {
        return new AuthException(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    public static AuthException accessDenied() {
        return new AuthException(ErrorCode.AUTH_ACCESS_DENIED);
    }

    public static AuthException accountLocked() {
        return new AuthException(ErrorCode.AUTH_ACCOUNT_LOCKED);
    }

    public static AuthException accountDisabled() {
        return new AuthException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }

    public static AuthException emailAlreadyExists(String email) {
        return new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Email already exists: " + email);
    }

    public static AuthException inactiveUser() {
        return new AuthException(ErrorCode.AUTH_ACCOUNT_DISABLED, "User is inactive");
    }

    public static AuthException resetTokenInvalid() {
        return new AuthException(ErrorCode.AUTH_RESET_TOKEN_INVALID);
    }

    public static AuthException resetTokenExpired() {
        return new AuthException(ErrorCode.AUTH_RESET_TOKEN_EXPIRED);
    }

    /**
     * Thrown when an IP exceeds the maximum allowed failed login attempts.
     * Returns HTTP 429 Too Many Requests with Retry-After info in the message.
     *
     * @param retryAfterSeconds seconds until the lockout window expires
     */
    public static AuthException tooManyRequests(long retryAfterSeconds) {
        return new AuthException(ErrorCode.AUTH_TOO_MANY_REQUESTS,
                "Too many failed login attempts. Try again in " + retryAfterSeconds + " seconds.");
    }

}
