package com.mtbs.auth.repository;

import com.mtbs.auth.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findAllByRoleId(Long roleId);

    void deleteAllByRoleId(Long roleId);

    @Query("SELECT p.name FROM RolePermission rp JOIN rp.permission p WHERE rp.role.id = :roleId")
    List<String> findPermissionNamesByRoleId(@Param("roleId") Long roleId);

    List<RolePermission> findByRoleId(Long roleId);

    @Query("SELECT rp FROM RolePermission rp JOIN FETCH rp.permission WHERE rp.role.id = :roleId")
    List<RolePermission> findByRoleIdWithPermissions(@Param("roleId") Long roleId);

    boolean existsByRoleIdAndPermissionId(Long roleId, Long permissionId);

    void deleteByRoleIdAndPermissionId(Long roleId, Long permissionId);
}
