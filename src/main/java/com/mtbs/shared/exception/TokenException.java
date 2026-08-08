package com.mtbs.shared.exception;

public class TokenException extends BaseException {

    public TokenException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public TokenException(ErrorCode errorCode) {
        super(errorCode);
    }

    public static TokenException invalid() {
        return new TokenException(ErrorCode.TOKEN_INVALID);
    }

    public static TokenException expired() {
        return new TokenException(ErrorCode.TOKEN_EXPIRED);
    }

    public static TokenException revoked() {
        return new TokenException(ErrorCode.TOKEN_REVOKED);
    }
}
