package com.mtbs.tenant.mapper;

import com.mtbs.tenant.dto.tenant.TenantResponse;
import com.mtbs.tenant.entity.Tenant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TenantMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "schemaName", source = "schemaName")
    @Mapping(target = "planId", source = "plan.id")
    @Mapping(target = "planCode", source = "plan.code")
    @Mapping(target = "planName", source = "plan.name")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    TenantResponse toResponse(Tenant entity);
}