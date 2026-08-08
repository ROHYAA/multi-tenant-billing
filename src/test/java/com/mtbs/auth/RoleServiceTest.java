package com.mtbs.auth;

import com.mtbs.app.MultiTenantBillingSystemApplication;
import com.mtbs.auth.dto.role.CreateRoleRequest;
import com.mtbs.auth.dto.role.RoleDetailResponse;
import com.mtbs.auth.dto.role.RoleResponse;
import com.mtbs.auth.dto.role.UpdateRoleRequest;
import com.mtbs.auth.dto.role.AssignPermissionRequest;
import com.mtbs.auth.entity.Permission;
import com.mtbs.auth.entity.Role;
import com.mtbs.auth.entity.User;
import com.mtbs.auth.repository.PermissionRepository;
import com.mtbs.auth.repository.RolePermissionRepository;
import com.mtbs.auth.repository.RoleRepository;
import com.mtbs.auth.repository.UserRepository;
import com.mtbs.auth.service.RoleService;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.support.TestDataBuilder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = MultiTenantBillingSystemApplication.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RoleService Integration Tests")
class RoleServiceTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestDataBuilder testDataBuilder;

    @Nested
    @DisplayName("createRole")
    class CreateRoleTests {

        @Test
        @DisplayName("createRole new role saves with correct name")
        void createRole_newRole_savesWithCorrectName() {
            CreateRoleRequest request = CreateRoleRequest.builder()
                .name("CUSTOM_ROLE")
                .build();

            RoleResponse response = roleService.createRole(request);

            assertNotNull(response);
            assertEquals("CUSTOM_ROLE", response.getName());
            assertEquals(0, response.getPermissionCount());
        }

        @Test
        @DisplayName("createRole duplicate name throws AlreadyExists")
        void createRole_duplicateName_throwsAlreadyExists() {
            CreateRoleRequest request = CreateRoleRequest.builder()
                .name("CUSTOM_ROLE")
                .build();
            roleService.createRole(request);

            assertThrows(ResourceException.class, () ->
                roleService.createRole(request)
            );
        }
    }

    @Nested
    @DisplayName("getAllRoles")
    class GetAllRolesTests {

        @Test
        @DisplayName("getAllRoles returns all roles including system roles")
        void getAllRoles_returnsAllRoles() {
            List<RoleResponse> roles = roleService.getAllRoles();

            assertFalse(roles.isEmpty());
            assertTrue(roles.stream().anyMatch(r -> r.getName().equals("OWNER")));
            assertTrue(roles.stream().anyMatch(r -> r.getName().equals("ADMIN")));
            assertTrue(roles.stream().anyMatch(r -> r.getName().equals("EMPLOYEE")));
        }
    }

    @Nested
    @DisplayName("getRoleById")
    class GetRoleByIdTests {

        @Test
        @DisplayName("getRoleById returns role with permissions")
        void getRoleById_returnsRoleWithPermissions() {
            RoleDetailResponse response = roleService.getRoleById(1L);

            assertNotNull(response);
            assertNotNull(response.getName());
            assertNotNull(response.getPermissions());
        }

        @Test
        @DisplayName("getRoleById not found throws ResourceException")
        void getRoleById_notFound_throwsResourceException() {
            assertThrows(ResourceException.class, () ->
                roleService.getRoleById(99999L)
            );
        }
    }

    @Nested
    @DisplayName("updateRole")
    class UpdateRoleTests {

        @Test
        @DisplayName("updateRole system role name change blocked")
        void updateRole_systemRole_nameChangeBlocked() {
            Role ownerRole = roleRepository.findByName("OWNER").orElseThrow();

            UpdateRoleRequest request = UpdateRoleRequest.builder()
                .name("NEW_OWNER_NAME")
                .build();

            assertThrows(ResourceException.class, () ->
                roleService.updateRole(ownerRole.getId(), request)
            );
        }

        @Test
        @DisplayName("updateRole custom role updates name")
        void updateRole_customRole_updatesName() {
            RoleResponse created = roleService.createRole(CreateRoleRequest.builder()
                .name("OLD_NAME")
                .build());

            RoleResponse updated = roleService.updateRole(created.getId(), 
                UpdateRoleRequest.builder().name("NEW_NAME").build());

            assertEquals("NEW_NAME", updated.getName());
        }
    }

    @Nested
    @DisplayName("deleteRole")
    class DeleteRoleTests {

        @Test
        @DisplayName("deleteRole system role throws AccessDenied")
        void deleteRole_systemRole_throwsAccessDenied() {
            Role ownerRole = roleRepository.findByName("OWNER").orElseThrow();

            assertThrows(ResourceException.class, () ->
                roleService.deleteRole(ownerRole.getId())
            );
        }

        @Test
        @DisplayName("deleteRole custom role with active users throws AccessDenied")
        void deleteRole_customRole_withActiveUsers_throwsAccessDenied() {
            RoleResponse customRole = roleService.createRole(CreateRoleRequest.builder()
                .name("TEST_ROLE")
                .build());

            Role role = roleRepository.findById(customRole.getId()).orElseThrow();
            User user = User.builder()
                .name("Test User")
                .email("test@test.com")
                .password("password")
                .role(role)
                .status(Status.ACTIVE)
                .build();
            userRepository.save(user);

            assertThrows(ResourceException.class, () ->
                roleService.deleteRole(customRole.getId())
            );
        }

        @Test
        @DisplayName("deleteRole custom role without users succeeds")
        void deleteRole_customRole_withoutUsers_succeeds() {
            RoleResponse customRole = roleService.createRole(CreateRoleRequest.builder()
                .name("DELETE_ME")
                .build());

            assertDoesNotThrow(() ->
                roleService.deleteRole(customRole.getId())
            );
        }
    }

    @Nested
    @DisplayName("assignPermissionToRole")
    class AssignPermissionTests {

        @Test
        @DisplayName("assignPermission to role stores role permission")
        void assignPermissions_toRole_storesRolePermissions() {
            Role customRole = roleRepository.save(Role.builder()
                .name("PERM_TEST_ROLE")
                .build());

            Permission permission = permissionRepository.findAll().get(0);

            RoleDetailResponse response = roleService.assignPermissionToRole(customRole.getId(),
                AssignPermissionRequest.builder().permissionId(permission.getId()).build());

            assertNotNull(response);
            assertTrue(response.getPermissions().stream()
                .anyMatch(p -> p.getId().equals(permission.getId())));
        }

        @Test
        @DisplayName("assignPermission duplicate throws AlreadyExists")
        void assignPermission_duplicate_throwsAlreadyExists() {
            Role customRole = roleRepository.save(Role.builder()
                .name("PERM_DUP_ROLE")
                .build());

            Permission permission = permissionRepository.findAll().get(0);
            roleService.assignPermissionToRole(customRole.getId(),
                AssignPermissionRequest.builder().permissionId(permission.getId()).build());

            assertThrows(ResourceException.class, () ->
                roleService.assignPermissionToRole(customRole.getId(),
                    AssignPermissionRequest.builder().permissionId(permission.getId()).build())
            );
        }
    }

    @Nested
    @DisplayName("removePermissionFromRole")
    class RemovePermissionTests {

        @Test
        @DisplayName("removePermission from role permission no longer in list")
        void removePermission_fromRole_permissionNoLongerInList() {
            Role customRole = roleRepository.save(Role.builder()
                .name("REMOVE_TEST_ROLE")
                .build());

            Permission permission = permissionRepository.findAll().get(0);
            roleService.assignPermissionToRole(customRole.getId(),
                AssignPermissionRequest.builder().permissionId(permission.getId()).build());

            roleService.removePermissionFromRole(customRole.getId(), permission.getId());

            RoleDetailResponse response = roleService.getRoleById(customRole.getId());
            assertFalse(response.getPermissions().stream()
                .anyMatch(p -> p.getId().equals(permission.getId())));
        }
    }

    @Nested
    @DisplayName("getPermissionsForRole")
    class GetPermissionsTests {

        @Test
        @DisplayName("getPermissionsForRole returns all assigned")
        void getPermissionsForRole_returnsAllAssigned() {
            var permissions = roleService.getPermissionsForRole(1L);

            assertNotNull(permissions);
        }
    }
}