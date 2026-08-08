package com.mtbs.auth.service;

import com.mtbs.auth.dto.role.*;
import com.mtbs.auth.dto.permission.PermissionResponse;
import com.mtbs.auth.entity.Permission;
import com.mtbs.auth.entity.Role;
import com.mtbs.auth.entity.RolePermission;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.auth.repository.PermissionRepository;
import com.mtbs.auth.repository.RolePermissionRepository;
import com.mtbs.auth.repository.RoleRepository;
import com.mtbs.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for handling Role and RolePermission operations.
 * TenantContext is automatically set by JwtAuthenticationFilter.
 * Permissions are fetched from the public schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final PermissionCacheService permissionCacheService;

    private static final Set<String> SYSTEM_ROLES = Set.of("OWNER", "ADMIN", "EMPLOYEE");

    public List<RoleResponse> getAllRoles() {
        log.info("Fetching all roles for tenant");
        return roleRepository.findAll().stream()
                .map(role -> {
                    int permCount = rolePermissionRepository.findByRoleId(role.getId()).size();
                    return RoleResponse.builder()
                            .id(role.getId())
                            .name(role.getName())
                            .permissionCount(permCount)
                            .build();
                })
                .collect(Collectors.toList());
    }

    public RoleDetailResponse getRoleById(Long roleId) {
        log.info("Fetching role details: {}", roleId);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> ResourceException.notFound("Role", String.valueOf(roleId)));

        List<PermissionResponse> permissions = getPermissionsForRole(roleId);

        return RoleDetailResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .permissions(permissions)
                .build();
    }

    @Transactional
    public RoleResponse createRole(CreateRoleRequest request) {
        log.info("Creating new role: {}", request.getName());
        if (roleRepository.existsByName(request.getName())) {
            throw ResourceException.alreadyExists("Role", request.getName());
        }

        Role role = new Role();
        role.setName(request.getName());
        role = roleRepository.saveAndFlush(role);

        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .permissionCount(0)
                .build();
    }

    @Transactional
    public RoleResponse updateRole(Long roleId, UpdateRoleRequest request) {
        log.info("Updating role: {}", roleId);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> ResourceException.notFound("Role", String.valueOf(roleId)));

        if (SYSTEM_ROLES.contains(role.getName().toUpperCase())) {
            throw ResourceException.accessDenied("Cannot modify system role: " + role.getName());
        }

        if (!role.getName().equals(request.getName()) && roleRepository.existsByName(request.getName())) {
            throw ResourceException.alreadyExists("Role", request.getName());
        }

        role.setName(request.getName());
        role = roleRepository.save(role);

        int permCount = rolePermissionRepository.findByRoleId(role.getId()).size();

        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .permissionCount(permCount)
                .build();
    }

    @Transactional
    public void deleteRole(Long roleId) {
        log.info("Deleting role: {}", roleId);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> ResourceException.notFound("Role", String.valueOf(roleId)));

        if (SYSTEM_ROLES.contains(role.getName().toUpperCase())) {
            throw ResourceException.accessDenied("Cannot delete system role: " + role.getName());
        }

        if (!userRepository.findByRoleId(roleId).isEmpty()) {
            throw ResourceException.accessDenied("Cannot delete role; it has assigned users.");
        }

        role.setDeleted(true);
        roleRepository.save(role);
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> getPermissionsForRole(Long roleId) {
        log.info("Fetching permissions for role: {}", roleId);
        return rolePermissionRepository.findByRoleIdWithPermissions(roleId).stream()
                .map(rp -> mapPermissionToResponse(rp.getPermission()))
                .collect(Collectors.toList());
    }

    @Transactional
    public RoleDetailResponse assignPermissionToRole(Long roleId, AssignPermissionRequest request) {
        log.info("Assigning permission {} to role {}", request.getPermissionId(), roleId);

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> ResourceException.notFound("Role", String.valueOf(roleId)));

        if (rolePermissionRepository.existsByRoleIdAndPermissionId(roleId, request.getPermissionId())) {
            throw ResourceException.alreadyExists("RolePermission", String.valueOf(request.getPermissionId()));
        }

        Permission permission = permissionRepository.findById(request.getPermissionId())
                .orElseThrow(() -> ResourceException.notFound("Permission", String.valueOf(request.getPermissionId())));

        RolePermission rp = new RolePermission();
        rp.setRole(role);
        rp.setPermission(permission);
        rolePermissionRepository.save(rp);

        permissionCacheService.evictTenant(TenantContext.getSchemaName());

        return getRoleById(roleId);
    }

    @Transactional
    public void removePermissionFromRole(Long roleId, Long permissionId) {
        log.info("Removing permission {} from role {}", permissionId, roleId);
        rolePermissionRepository.deleteByRoleIdAndPermissionId(roleId, permissionId);
        permissionCacheService.evictTenant(TenantContext.getSchemaName());
    }

    private PermissionResponse mapPermissionToResponse(Permission permission) {
        return PermissionResponse.builder()
                .id(permission.getId())
                .name(permission.getName())
                .description(permission.getDescription())
                .build();
    }
}
