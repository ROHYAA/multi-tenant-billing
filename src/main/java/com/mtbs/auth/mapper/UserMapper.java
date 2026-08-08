package com.mtbs.auth.mapper;

import com.mtbs.auth.dto.user.UserResponse;
import com.mtbs.auth.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "roleName", ignore = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    UserResponse toResponse(User entity);

    default UserResponse toResponseWithRole(User entity) {
        UserResponse response = toResponse(entity);
        if (entity.getRole() != null) {
            response.setRoleName(entity.getRole().getName());
        }
        return response;
    }
}
