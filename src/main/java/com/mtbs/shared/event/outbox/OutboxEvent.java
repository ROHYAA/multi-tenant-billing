package com.mtbs.shared.event.outbox;

import com.mtbs.shared.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 100)
    private String aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "event_class", nullable = false, length = 255)
    private String eventClass;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 5;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    public void markProcessed() {
        this.status = OutboxStatus.PROCESSED;
        this.processedAt = Instant.now();
        this.lockedUntil = null;
    }

    public void markProcessing() {
        this.status = OutboxStatus.PROCESSING;
        this.lockedUntil = Instant.now().plusSeconds(30);
    }

    public void incrementRetryCount() {
        this.retryCount = this.retryCount + 1;
    }

    public void scheduleRetry(long backoffSeconds) {
        this.status = OutboxStatus.PENDING;
        this.lockedUntil = Instant.now().plusSeconds(backoffSeconds);
    }

    public void markFailed(ErrorType errorType, String error) {
        this.lastError = error;
        this.incrementRetryCount();

        if (errorType == ErrorType.PERMANENT || errorType == ErrorType.VALIDATION) {
            this.status = OutboxStatus.FAILED;
        } else if (this.retryCount >= this.maxRetries) {
            this.status = OutboxStatus.FAILED;
        } else {
            this.scheduleRetry(calculateBackoff());
        }
    }

    public void markFailed(String error) {
        markFailed(ErrorType.UNKNOWN, error);
    }

    public void markFailed() {
        this.status = OutboxStatus.FAILED;
        this.lockedUntil = null;
    }

    public boolean canRetry() {
        return this.retryCount < this.maxRetries;
    }

    private long calculateBackoff() {
        if (this.retryCount <= 1) {
            return 60; // 1 minute
        }
        return (long) Math.pow(5, this.retryCount - 1) * 60; // 5, 25, 125...
    }

    public enum ErrorType {
        TRANSIENT,
        VALIDATION,
        PERMANENT,
        UNKNOWN
    }
}
