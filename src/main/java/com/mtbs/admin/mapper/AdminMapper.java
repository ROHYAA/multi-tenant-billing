package com.mtbs.admin.mapper;

import com.mtbs.admin.dto.AuditLogResponse;
import com.mtbs.admin.entity.AuditLog;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "whoUserId", source = "whoUserId")
    @Mapping(target = "whoUserEmail", source = "whoUserEmail")
    @Mapping(target = "whoUserName", source = "whoUserName")
    @Mapping(target = "whoRole", source = "whoRole")
    @Mapping(target = "whatAction", source = "whatAction")
    @Mapping(target = "whatDescription", source = "whatAction")
    @Mapping(target = "whereEntityType", source = "whereEntityType")
    @Mapping(target = "whereEntityId", source = "whereEntityId")
    @Mapping(target = "whereEntityName", source = "whereEntityName")
    @Mapping(target = "changesBefore", source = "changesBefore")
    @Mapping(target = "changesAfter", source = "changesAfter")
    @Mapping(target = "changesSummary", source = "changesSummary")
    @Mapping(target = "contextTenantId", source = "contextTenantId")
    @Mapping(target = "contextTenantName", source = "contextTenantName")
    @Mapping(target = "contextIpAddress", source = "contextIpAddress")
    @Mapping(target = "contextUserAgent", source = "contextUserAgent")
    @Mapping(target = "contextMetadata", source = "contextMetadata")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "module", source = "module")
    @Mapping(target = "severity", source = "severity")
    @Mapping(target = "createdAt", source = "createdAt")
    AuditLogResponse toResponse(AuditLog entity);
}
