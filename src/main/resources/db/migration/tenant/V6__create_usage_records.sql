-- V6: Create usage_records table in tenant schema
CREATE TABLE IF NOT EXISTS usage_records (
    id                      BIGSERIAL       PRIMARY KEY,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by              BIGINT,
    updated_by              BIGINT,
    subscription_id         BIGINT          NOT NULL REFERENCES subscriptions(id),
    tenant_id               BIGINT          NOT NULL DEFAULT 0,
    metric_type             VARCHAR(50)     NOT NULL,
    quantity                BIGINT          NOT NULL DEFAULT 0,
    value_bytes             BIGINT          NOT NULL DEFAULT 0,
    is_billed               BOOLEAN         NOT NULL DEFAULT FALSE,
    recorded_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    billing_period_start    TIMESTAMPTZ     NOT NULL,
    billing_period_end      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_usage_records_subscription_id ON usage_records (subscription_id);
CREATE INDEX IF NOT EXISTS idx_usage_records_metric_type     ON usage_records (metric_type);
CREATE INDEX IF NOT EXISTS idx_usage_records_billing_period  ON usage_records (billing_period_start, billing_period_end);
CREATE INDEX IF NOT EXISTS idx_usage_records_metric_tenant   ON usage_records (tenant_id, metric_type, billing_period_start) WHERE deleted = FALSE;

COMMENT ON COLUMN usage_records.tenant_id IS 'Tenant ID for schema-per-tenant usage queries.';
COMMENT ON COLUMN usage_records.value_bytes IS 'Accumulated bytes for STORAGE_GB metric. Zero for all other metrics.';
COMMENT ON COLUMN usage_records.is_billed IS 'Indicates if this usage record has been included in a billing cycle.';
