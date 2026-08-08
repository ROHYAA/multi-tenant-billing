package com.mtbs.tenant.repository;

import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySchemaName(String schemaName);

    Optional<Tenant> findByName(String name);

    boolean existsBySchemaName(String schemaName);

    boolean existsByName(String name);

    List<Tenant> findAllByStatus(Status status);

    Page<Tenant> findByStatus(Status status, Pageable pageable);

    boolean existsByOwnerEmail(String email);

    boolean existsBySlug(String slug);

    Optional<Tenant> findBySlug(String slug);

    @Query("SELECT t FROM Tenant t WHERE t.plan.id = :planId AND t.deleted = false")
    List<Tenant> findByPlanIdAndDeletedFalse(@Param("planId") Long planId);

    @Query("SELECT t FROM Tenant t WHERE t.plan.code = :code AND t.deleted = false")
    List<Tenant> findByPlanCodeAndDeletedFalse(@Param("code") String code);

    @Query("SELECT t FROM Tenant t LEFT JOIN FETCH t.plan WHERE t.id = :id")
    Optional<Tenant> findByIdWithPlan(@Param("id") Long id);
}