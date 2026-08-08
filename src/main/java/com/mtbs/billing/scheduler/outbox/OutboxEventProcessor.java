package com.mtbs.billing.scheduler.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtbs.billing.repository.OutboxEventRepository;
import com.mtbs.shared.event.outbox.OutboxEvent;
import com.mtbs.shared.event.outbox.OutboxEvent.ErrorType;
import com.mtbs.shared.event.outbox.OutboxStatus;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventProcessor {

    private static final String LOG_PREFIX = "OUTBOX";

    private final OutboxEventRepository outboxRepository;
    private final TenantService tenantService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    @Value("${app.outbox.poll-interval-ms:5000}")
    private long pollIntervalMs;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    public void processOutbox() {
        List<Tenant> tenants = tenantService.getTenantsByStatusList(Status.ACTIVE);

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());

                processOutboxForTenant();

            } catch (Exception e) {
                log.error("{} ERROR tenant={} error={}",
                        LOG_PREFIX, tenant.getSchemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    public void processOutboxForTenant() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();

            int recovered = outboxRepository.resetStaleEvents(now.minus(Duration.ofSeconds(30)));
            if (recovered > 0) {
                log.info("{} RECOVERED tenant={} count={}", LOG_PREFIX, TenantContext.getSchemaName(), recovered);
            }

            List<OutboxEvent> events = outboxRepository.findPendingForUpdate(now, batchSize);

            if (events.isEmpty()) {
                return;
            }

            log.info("{} PROCESSING tenant={} count={}", LOG_PREFIX, TenantContext.getSchemaName(), events.size());

            for (OutboxEvent outboxEvent : events) {
                processEvent(outboxEvent);
            }
        });
    }

    @Scheduled(cron = "${app.outbox.cleanup-cron:0 0 3 * * ?}")
    public void cleanupProcessedEvents() {
        List<Tenant> tenants = tenantService.getTenantsByStatusList(Status.ACTIVE);

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                TenantContext.setCurrentSchema(tenant.getSchemaName());

                cleanupProcessedEventsForTenant();

            } catch (Exception e) {
                log.error("{} CLEANUP_ERROR tenant={} error={}",
                        LOG_PREFIX, tenant.getSchemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    public void cleanupProcessedEventsForTenant() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant cutoff = Instant.now().minus(Duration.ofDays(30));
            int deleted = outboxRepository.deleteByStatusAndProcessedAtBefore(
                    OutboxStatus.PROCESSED, cutoff);
            if (deleted > 0) {
                log.info("{} CLEANUP tenant={} deleted={}", LOG_PREFIX, TenantContext.getSchemaName(), deleted);
            }
        });
    }

    private void processEvent(OutboxEvent outboxEvent) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        log.info("{} PICKED correlationId={} eventType={} aggregateType={} aggregateId={} retryCount={}",
                LOG_PREFIX, correlationId, outboxEvent.getEventType(),
                outboxEvent.getAggregateType(), outboxEvent.getAggregateId(),
                outboxEvent.getRetryCount());

        long startTime = System.currentTimeMillis();

        try {
            outboxEvent.markProcessing();
            outboxRepository.save(outboxEvent);

            Object event = deserialize(outboxEvent);
            eventPublisher.publishEvent(event);

            outboxEvent.markProcessed();
            outboxRepository.save(outboxEvent);

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("{} SUCCESS correlationId={} eventType={} durationMs={}",
                    LOG_PREFIX, correlationId, outboxEvent.getEventType(), durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            handleFailure(outboxEvent, e, correlationId, durationMs);
        }
    }

    private void handleFailure(OutboxEvent outboxEvent, Exception e, String correlationId, long durationMs) {
        ErrorType errorType = classifyError(e);

        log.error("{} FAILED correlationId={} eventType={} errorType={} error={} durationMs={}",
                LOG_PREFIX, correlationId, outboxEvent.getEventType(),
                errorType, e.getMessage(), durationMs);

        outboxEvent.markFailed(errorType, e.getMessage());
        outboxRepository.save(outboxEvent);

        if (outboxEvent.getStatus() == OutboxStatus.FAILED) {
            log.error("{} DEAD_LETTER correlationId={} eventType={} totalRetries={} finalError={}",
                    LOG_PREFIX, correlationId, outboxEvent.getEventType(),
                    outboxEvent.getRetryCount(), e.getMessage());
        } else {
            long backoffSeconds = calculateBackoff(outboxEvent.getRetryCount());
            log.warn("{} RETRY_SCHEDULED correlationId={} eventType={} retryCount={} backoffSeconds={}",
                    LOG_PREFIX, correlationId, outboxEvent.getEventType(),
                    outboxEvent.getRetryCount(), backoffSeconds);
        }
    }

    private ErrorType classifyError(Exception e) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return ErrorType.VALIDATION;
        }
        if (e instanceof jakarta.mail.MessagingException) {
            return ErrorType.TRANSIENT;
        }
        if (e.getCause() instanceof java.net.SocketTimeoutException ||
            e.getCause() instanceof java.net.ConnectException) {
            return ErrorType.TRANSIENT;
        }
        if (e instanceof RuntimeException && e.getMessage() != null &&
            e.getMessage().contains("Failed to deserialize")) {
            return ErrorType.PERMANENT;
        }
        return ErrorType.UNKNOWN;
    }

    private long calculateBackoff(int retryCount) {
        if (retryCount <= 1) {
            return 60;
        }
        return (long) Math.pow(5, retryCount - 1) * 60;
    }

    private Object deserialize(OutboxEvent outboxEvent) {
        try {
            Class<?> clazz = Class.forName(outboxEvent.getEventClass());
            return objectMapper.readValue(outboxEvent.getPayload(), clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize outbox event: " +
                    outboxEvent.getEventType() + " - " + e.getMessage(), e);
        }
    }
}
