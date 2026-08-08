package com.mtbs.business.invoice.mapper;

import com.mtbs.business.invoice.dto.BillItemResponse;
import com.mtbs.business.invoice.entity.BillItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BillItemMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPrice")
    @Mapping(target = "taxPercentage", source = "taxPercentage")
    @Mapping(target = "taxAmount", source = "taxAmount")
    @Mapping(target = "total", source = "total")
    BillItemResponse toResponse(BillItem entity);
}
