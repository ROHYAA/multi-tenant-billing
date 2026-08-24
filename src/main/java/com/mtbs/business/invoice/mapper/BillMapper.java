package com.mtbs.business.invoice.mapper;

import com.mtbs.business.invoice.dto.BillResponse;
import com.mtbs.business.invoice.entity.Bill;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BillMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "invoiceNumber", source = "invoiceNumber")
    @Mapping(target = "customerId", source = "customerId")
    @Mapping(target = "customerName", ignore = true)
    @Mapping(target = "customerEmail", ignore = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "subtotal", source = "subtotal")
    @Mapping(target = "taxAmount", source = "taxAmount")
    @Mapping(target = "discountAmount", source = "discountAmount")
    @Mapping(target = "totalAmount", source = "totalAmount")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "dueDate", source = "dueDate")
    @Mapping(target = "paidAt", source = "paidAt")
    @Mapping(target = "items", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    BillResponse toResponse(Bill entity);

    default BillResponse toResponseWithCustomer(Bill entity, String customerName, String customerEmail) {
        BillResponse response = toResponse(entity);
        response.setCustomerName(customerName);
        response.setCustomerEmail(customerEmail);
        return response;
    }
}
