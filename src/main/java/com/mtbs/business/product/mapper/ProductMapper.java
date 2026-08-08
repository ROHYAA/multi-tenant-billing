package com.mtbs.business.product.mapper;

import com.mtbs.business.product.dto.ProductResponse;
import com.mtbs.business.product.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "price", source = "price")
    @Mapping(target = "taxPercentage", source = "taxPercentage")
    @Mapping(target = "hsnSacCode", source = "hsnSacCode")
    @Mapping(target = "unit", source = "unit")
    @Mapping(target = "isActive", source = "isActive")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    ProductResponse toResponse(Product entity);
}
