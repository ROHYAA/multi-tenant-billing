package com.mtbs.billing.mapper;

import com.mtbs.billing.dto.InvoiceResponse;
import com.mtbs.billing.entity.Invoice;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InvoiceMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "subscriptionId", source = "subscriptionId")
    @Mapping(target = "invoiceNumber", source = "invoiceNumber")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "subtotal", source = "subtotal")
    @Mapping(target = "taxAmount", source = "taxAmount")
    @Mapping(target = "discountAmount", source = "discountAmount")
    @Mapping(target = "totalAmount", source = "totalAmount")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "dueDate", source = "dueDate")
    @Mapping(target = "paidAt", source = "paidAt")
    @Mapping(target = "billingPeriodStart", source = "billingPeriodStart")
    @Mapping(target = "billingPeriodEnd", source = "billingPeriodEnd")
    @Mapping(target = "lineItems", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    InvoiceResponse toResponse(Invoice entity);
}
