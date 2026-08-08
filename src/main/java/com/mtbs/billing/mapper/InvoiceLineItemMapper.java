package com.mtbs.billing.mapper;

import com.mtbs.billing.dto.InvoiceLineItemResponse;
import com.mtbs.billing.entity.InvoiceLineItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InvoiceLineItemMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPrice")
    @Mapping(target = "totalPrice", source = "totalPrice")
    @Mapping(target = "lineItemType", source = "lineItemType")
    InvoiceLineItemResponse toResponse(InvoiceLineItem entity);
}
