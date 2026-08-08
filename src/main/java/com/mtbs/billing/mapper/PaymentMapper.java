package com.mtbs.billing.mapper;

import com.mtbs.billing.dto.PaymentResponse;
import com.mtbs.billing.entity.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "invoiceId", source = "invoiceId")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "paymentMethod", source = "paymentMethod")
    @Mapping(target = "razorpayOrderId", source = "razorpayOrderId")
    @Mapping(target = "razorpayPaymentId", source = "razorpayPaymentId")
    @Mapping(target = "failureCode", source = "failureCode")
    @Mapping(target = "failureMessage", source = "failureMessage")
    @Mapping(target = "retryCount", source = "retryCount")
    @Mapping(target = "nextRetryAt", source = "nextRetryAt")
    @Mapping(target = "paidAt", source = "paidAt")
    @Mapping(target = "createdAt", source = "createdAt")
    PaymentResponse toResponse(Payment entity);
}
