package com.mtbs.business.invoice.mapper;

import com.mtbs.business.invoice.dto.BusinessInvoiceItemResponse;
import com.mtbs.business.invoice.entity.BusinessInvoiceItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BusinessInvoiceItemMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPrice")
    @Mapping(target = "taxPercentage", source = "taxPercentage")
    @Mapping(target = "taxAmount", source = "taxAmount")
    @Mapping(target = "total", source = "total")
    BusinessInvoiceItemResponse toResponse(BusinessInvoiceItem entity);
}
