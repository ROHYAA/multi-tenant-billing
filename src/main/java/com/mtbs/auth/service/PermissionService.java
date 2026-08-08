package com.mtbs.auth.service;

import com.mtbs.auth.dto.permission.CreatePermissionRequest;
import com.mtbs.auth.dto.permission.PermissionResponse;
import com.mtbs.auth.entity.Permission;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.auth.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for handling Permissions globally across the public schema.
 * Operates without TenantContext.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final PermissionRepository permissionRepository;

    private static final Set<String> SYSTEM_PERMISSIONS = Set.of(
            "TENANT_VIEW",
            "TENANT_MANAGE",
            "USER_VIEW",
            "USER_MANAGE",
            "USER_DELETE",
            "ROLE_VIEW",
            "ROLE_MANAGE",
            "BILLING_MANAGE",
            "CUSTOMER_MANAGE",
            "PRODUCT_MANAGE");

    public List<PermissionResponse> getAllPermissions() {
        log.info("Fetching all permissions from public schema");
        return permissionRepository.findAll().stream()
                .filter(p -> !p.getDeleted())
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public PermissionResponse getPermissionById(Long id) {
        log.info("Fetching permission by id: {}", id);
        Permission permission = permissionRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Permission", String.valueOf(id)));

        return mapToResponse(permission);
    }

    @Transactional
    public PermissionResponse createPermission(CreatePermissionRequest request) {
        log.info("Creating new custom permission: {}", request.getName());

        if (permissionRepository.existsByName(request.getName())) {
            throw ResourceException.alreadyExists("Permission", request.getName());
        }

        Permission permission = new Permission();
        permission.setName(request.getName());
        permission.setDescription(request.getDescription());
        permission = permissionRepository.save(permission);

        return mapToResponse(permission);
    }

    @Transactional
    public void deletePermission(Long id) {
        log.info("Soft deleting permission id: {}", id);

        Permission permission = permissionRepository.findById(id)
                .orElseThrow(() -> ResourceException.notFound("Permission", String.valueOf(id)));

        if (SYSTEM_PERMISSIONS.contains(permission.getName())) {
            throw ResourceException
                    .accessDenied("Cannot delete a core system permission: " + permission.getName());
        }

        permission.setDeleted(true);
        permissionRepository.save(permission);
    }

    private PermissionResponse mapToResponse(Permission permission) {
        return PermissionResponse.builder()
                .id(permission.getId())
                .name(permission.getName())
                .description(permission.getDescription())
                .build();
    }
}
