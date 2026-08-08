-- V7: Create usage_summaries table in tenant schema
CREATE TABLE IF NOT EXISTS usage_summaries (
    id                      BIGSERIAL       PRIMARY KEY,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by              BIGINT,
    updated_by              BIGINT,
    subscription_id         BIGINT          NOT NULL REFERENCES subscriptions(id),
    metric_type             VARCHAR(50)     NOT NULL,
    total_quantity          BIGINT          NOT NULL DEFAULT 0,
    billing_period_start    TIMESTAMPTZ     NOT NULL,
    billing_period_end      TIMESTAMPTZ     NOT NULL,
    is_billed               BOOLEAN         NOT NULL DEFAULT FALSE
    );

CREATE INDEX IF NOT EXISTS idx_usage_summaries_subscription_id     ON usage_summaries (subscription_id);
CREATE INDEX IF NOT EXISTS idx_usage_summaries_metric_billing       ON usage_summaries (metric_type, billing_period_start, billing_period_end);
