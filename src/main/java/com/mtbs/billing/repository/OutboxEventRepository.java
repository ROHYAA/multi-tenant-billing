package com.mtbs.billing.repository;

import com.mtbs.shared.event.outbox.OutboxEvent;
import com.mtbs.shared.event.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
        SELECT * FROM outbox_events
        WHERE status = 'PENDING'
          AND (locked_until IS NULL OR locked_until < :now)
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findPendingForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = :status
          AND (e.lockedUntil IS NULL OR e.lockedUntil < :now)
        ORDER BY e.createdAt ASC
        LIMIT :limit
        """)
    List<OutboxEvent> findPendingEvents(
            @Param("status") OutboxStatus status,
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Modifying
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = 'PENDING', e.lockedUntil = NULL
        WHERE e.status = 'PROCESSING'
          AND e.lockedUntil < :threshold
        """)
    int resetStaleEvents(@Param("threshold") Instant threshold);

    long countByStatus(OutboxStatus status);

    @Modifying
    @Query("""
        DELETE FROM OutboxEvent e
        WHERE e.status = :status
          AND e.processedAt < :cutoff
        """)
    int deleteByStatusAndProcessedAtBefore(
            @Param("status") OutboxStatus status,
            @Param("cutoff") Instant cutoff);

    boolean existsByIdempotencyKey(String idempotencyKey);

    long countByStatusAndEventType(OutboxStatus status, String eventType);
}
