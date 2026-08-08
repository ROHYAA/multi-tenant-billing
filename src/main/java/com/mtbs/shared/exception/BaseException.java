package com.mtbs.shared.exception;

import lombok.Getter;

@Getter
public abstract class BaseException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    protected BaseException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + (detail != null ? ": " + detail : ""));
        this.errorCode = errorCode;
        this.detail = detail;
    }

    protected BaseException(ErrorCode errorCode) {
        this(errorCode, null);
    }
}
