package com.mtbs.shared.exception;

public class PaymentException extends BaseException {

    public PaymentException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public PaymentException(ErrorCode errorCode) {
        super(errorCode);
    }

    public static PaymentException paymentFailed(String reason) {
        return new PaymentException(ErrorCode.PAYMENT_FAILED, reason);
    }

    public static PaymentException paymentAlreadyProcessed() {
        return new PaymentException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }

    public static PaymentException invalidPaymentMethod() {
        return new PaymentException(ErrorCode.INVALID_PAYMENT_METHOD);
    }

    public static PaymentException razorpayError(String code, String message) {
        return new PaymentException(ErrorCode.RAZORPAY_ERROR, code + ": " + message);
    }

    public static PaymentException invalidSignature() {
        return new PaymentException(ErrorCode.INVALID_PAYMENT_SIGNATURE,
                "Razorpay payment signature verification failed");
    }

    public static PaymentException orderCreationFailed(String reason) {
        return new PaymentException(ErrorCode.ORDER_CREATION_FAILED, reason);
    }
}
