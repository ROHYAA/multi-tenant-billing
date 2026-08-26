package com.mtbs.tenant.mapper;

import com.mtbs.tenant.dto.tenant.ShopResponse;
import com.mtbs.tenant.entity.Shop;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ShopMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "schemaName", source = "schemaName")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "planName", source = "planName")
    @Mapping(target = "subscriptionExpiresAt", source = "subscriptionExpiresAt")
    @Mapping(target = "createdAt", source = "createdAt")
    ShopResponse toResponse(Shop entity);
}