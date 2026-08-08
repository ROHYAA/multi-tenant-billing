-- V10: Create payments table in tenant schema
CREATE TABLE IF NOT EXISTS payments (
    id                      BIGSERIAL       PRIMARY KEY,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by              BIGINT,
    updated_by              BIGINT,
    invoice_id              BIGINT          NOT NULL REFERENCES invoices(id),
    amount                  NUMERIC(12,2)   NOT NULL,
    currency                VARCHAR(3)      NOT NULL DEFAULT 'INR',
    status                  VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    payment_method          VARCHAR(50),
    razorpay_order_id       VARCHAR(255),
    razorpay_payment_id     VARCHAR(255),
    razorpay_signature      VARCHAR(255),
    idempotency_key         VARCHAR(255)    NOT NULL UNIQUE,
    failure_code            VARCHAR(100),
    failure_message         TEXT,
    retry_count             INT             NOT NULL DEFAULT 0,
    next_retry_at           TIMESTAMPTZ,
    paid_at                 TIMESTAMPTZ
    );

CREATE INDEX IF NOT EXISTS idx_payments_invoice_id      ON payments (invoice_id);
CREATE INDEX IF NOT EXISTS idx_payments_status          ON payments (status);
CREATE INDEX IF NOT EXISTS idx_payments_idempotency_key ON payments (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payments_next_retry_at   ON payments (next_retry_at);
