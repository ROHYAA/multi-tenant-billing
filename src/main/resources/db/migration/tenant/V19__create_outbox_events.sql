-- V20: Transactional Outbox Pattern for reliable event publishing
-- Ensures at-least-once delivery of all domain events
-- Includes idempotency support and FOR UPDATE SKIP LOCKED for concurrency

CREATE TABLE IF NOT EXISTS outbox_events (
    -- Base entity fields
    id                      BIGSERIAL       PRIMARY KEY,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by              BIGINT,
    updated_by              BIGINT,

    -- Event identification
    event_type              VARCHAR(100)     NOT NULL,
    aggregate_type          VARCHAR(100),
    aggregate_id            VARCHAR(100),

    -- Event payload (JSON)
    payload                 TEXT            NOT NULL,
    event_class             VARCHAR(255)    NOT NULL,

    -- Idempotency support
    idempotency_key        VARCHAR(255),

    -- Processing state
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                          CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    retry_count             INTEGER         NOT NULL DEFAULT 0,
    max_retries             INTEGER         NOT NULL DEFAULT 5,
    last_error              TEXT,

    -- Processing timestamps
    processed_at            TIMESTAMPTZ,

    -- Distributed lock (prevents duplicate processing in scaled environments)
    locked_until            TIMESTAMPTZ
);

-- Indexes for efficient polling
CREATE INDEX IF NOT EXISTS idx_outbox_status_locked
    ON outbox_events (status, locked_until)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX IF NOT EXISTS idx_outbox_event_type
    ON outbox_events (event_type);

CREATE INDEX IF NOT EXISTS idx_outbox_created_at
    ON outbox_events (created_at);

-- Index for monitoring failed events
CREATE INDEX IF NOT EXISTS idx_outbox_failed
    ON outbox_events (status)
    WHERE status = 'FAILED';

-- Index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_outbox_processed_at
    ON outbox_events (processed_at)
    WHERE status = 'PROCESSED';

-- Idempotency index - prevents duplicate event processing
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency
    ON outbox_events (aggregate_type, aggregate_id, event_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
