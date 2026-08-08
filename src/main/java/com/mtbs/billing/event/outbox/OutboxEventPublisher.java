package com.mtbs.billing.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtbs.billing.repository.OutboxEventRepository;
import com.mtbs.shared.event.DomainEvent;
import com.mtbs.shared.event.outbox.OutboxEvent;
import com.mtbs.shared.event.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.outbox.max-retries:5}")
    private int maxRetries;

    public void save(DomainEvent event) {
        save(event, event.getClass().getSimpleName(), null);
    }

    public void save(DomainEvent event, String aggregateType, Object aggregateId) {
        String aggId = aggregateId != null ? aggregateId.toString() : null;
        String idempotencyKey = buildIdempotencyKey(event, aggregateType, aggId);

        if (idempotencyKey != null && outboxRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.debug("Duplicate event skipped: type={}, aggregateType={}, aggregateId={}",
                    event.getEventType(), aggregateType, aggId);
            return;
        }

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .eventType(event.getEventType())
                .aggregateType(aggregateType)
                .aggregateId(aggId)
                .eventClass(event.getClass().getName())
                .payload(serialize(event))
                .idempotencyKey(idempotencyKey)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .maxRetries(maxRetries)
                .build();

        outboxRepository.save(outboxEvent);
        log.debug("Outbox event saved: type={}, aggregateType={}, aggregateId={}, idempotencyKey={}",
                event.getEventType(), aggregateType, aggId, idempotencyKey);
    }

    public void saveWithoutIdempotency(DomainEvent event, String aggregateType, Object aggregateId) {
        String aggId = aggregateId != null ? aggregateId.toString() : null;

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .eventType(event.getEventType())
                .aggregateType(aggregateType)
                .aggregateId(aggId)
                .eventClass(event.getClass().getName())
                .payload(serialize(event))
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .maxRetries(maxRetries)
                .build();

        outboxRepository.save(outboxEvent);
        log.debug("Outbox event saved (no idempotency): type={}, aggregateType={}, aggregateId={}",
                event.getEventType(), aggregateType, aggId);
    }

    private String buildIdempotencyKey(DomainEvent event, String aggregateType, String aggregateId) {
        if (aggregateType == null && aggregateId == null) {
            return null;
        }
        String dateKey = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        return String.format("%s:%s:%s:%s", aggregateType, aggregateId, event.getEventType(), dateKey);
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event: " + event.getClass().getName(), e);
        }
    }
}
