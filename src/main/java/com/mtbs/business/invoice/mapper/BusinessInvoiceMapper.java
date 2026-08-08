package com.mtbs.business.invoice.mapper;

import com.mtbs.business.invoice.dto.BusinessInvoiceResponse;
import com.mtbs.business.invoice.entity.BusinessInvoice;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BusinessInvoiceMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "invoiceNumber", source = "invoiceNumber")
    @Mapping(target = "customerId", source = "customerId")
    @Mapping(target = "customerName", ignore = true)
    @Mapping(target = "customerEmail", ignore = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "subtotal", source = "subtotal")
    @Mapping(target = "taxAmount", source = "taxAmount")
    @Mapping(target = "totalAmount", source = "totalAmount")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "dueDate", source = "dueDate")
    @Mapping(target = "paidAt", source = "paidAt")
    @Mapping(target = "razorpayPaymentLinkId", source = "razorpayPaymentLinkId")
    @Mapping(target = "items", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    BusinessInvoiceResponse toResponse(BusinessInvoice entity);

    default BusinessInvoiceResponse toResponseWithCustomer(BusinessInvoice entity, String customerName, String customerEmail) {
        BusinessInvoiceResponse response = toResponse(entity);
        response.setCustomerName(customerName);
        response.setCustomerEmail(customerEmail);
        return response;
    }
}
