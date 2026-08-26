package com.mtbs.business.payment.mapper;

import com.mtbs.business.payment.dto.PaymentResponse;
import com.mtbs.business.payment.entity.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "invoiceId", source = "invoiceId")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "method", source = "method")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "paidAt", source = "paidAt")
    @Mapping(target = "createdAt", source = "createdAt")
    PaymentResponse toResponse(Payment entity);
}
