package com.mtbs.business.payment.mapper;

import com.mtbs.business.payment.dto.BusinessPaymentResponse;
import com.mtbs.business.payment.entity.BusinessPayment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BusinessPaymentMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "invoiceId", source = "invoiceId")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "method", source = "method")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "paidAt", source = "paidAt")
    @Mapping(target = "razorpayPaymentLinkId", source = "razorpayPaymentLinkId")
    @Mapping(target = "createdAt", source = "createdAt")
    BusinessPaymentResponse toResponse(BusinessPayment entity);
}
