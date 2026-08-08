package com.mtbs.admin.repository;

import com.mtbs.admin.entity.AuditLog;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, 
        JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findByWhoUserIdOrderByCreatedAtDesc(Long whoUserId, Pageable pageable);

    Page<AuditLog> findByWhereEntityTypeAndWhereEntityIdOrderByCreatedAtDesc(
            AuditEntityType whereEntityType, Long whereEntityId, Pageable pageable);

    Page<AuditLog> findByWhatActionOrderByCreatedAtDesc(AuditAction whatAction, Pageable pageable);

    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            Instant startDate, Instant endDate, Pageable pageable);

    Page<AuditLog> findByContextTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:whoUserId IS NULL OR a.whoUserId = :whoUserId)
          AND (:whatAction IS NULL OR a.whatAction = :whatAction)
          AND (:whereEntityType IS NULL OR a.whereEntityType = :whereEntityType)
          AND (:whereEntityId IS NULL OR a.whereEntityId = :whereEntityId)
          AND (:contextTenantId IS NULL OR a.contextTenantId = :contextTenantId)
          AND (:startDate IS NULL OR a.createdAt >= :startDate)
          AND (:endDate IS NULL OR a.createdAt <= :endDate)
          AND (:module IS NULL OR a.module = :module)
        ORDER BY a.createdAt DESC
        """)
    Page<AuditLog> searchAuditLogs(
            @Param("whoUserId") Long whoUserId,
            @Param("whatAction") AuditAction whatAction,
            @Param("whereEntityType") AuditEntityType whereEntityType,
            @Param("whereEntityId") Long whereEntityId,
            @Param("contextTenantId") Long contextTenantId,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            @Param("module") String module,
            Pageable pageable);

    @Query("""
        SELECT COUNT(a) FROM AuditLog a
        WHERE a.createdAt >= :since
        """)
    long countLogsSince(@Param("since") Instant since);

    @Query("""
        SELECT a.whatAction, COUNT(a) 
        FROM AuditLog a
        WHERE a.createdAt >= :since
        GROUP BY a.whatAction
        ORDER BY COUNT(a) DESC
        """)
    List<Object[]> getActionStatistics(@Param("since") Instant since);

    @Query("""
        SELECT a.whoUserId, a.whoUserEmail, COUNT(a) as actionCount
        FROM AuditLog a
        WHERE a.createdAt >= :since
        GROUP BY a.whoUserId, a.whoUserEmail
        ORDER BY actionCount DESC
        """)
    List<Object[]> getTopAdmins(@Param("since") Instant since, Pageable pageable);
}
