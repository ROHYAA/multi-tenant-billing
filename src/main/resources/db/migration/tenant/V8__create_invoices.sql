-- V8: Create invoices table in tenant schema
CREATE TABLE IF NOT EXISTS invoices (
    id                      BIGSERIAL       PRIMARY KEY,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by              BIGINT,
    updated_by              BIGINT,
    subscription_id         BIGINT          NOT NULL REFERENCES subscriptions(id),
    invoice_number          VARCHAR(50)     NOT NULL UNIQUE,
    status                  VARCHAR(50)     NOT NULL DEFAULT 'DRAFT',
    subtotal                NUMERIC(12,2)   NOT NULL DEFAULT 0,
    tax_amount              NUMERIC(12,2)   NOT NULL DEFAULT 0,
    discount_amount         NUMERIC(12,2)   NOT NULL DEFAULT 0,
    total_amount            NUMERIC(12,2)   NOT NULL DEFAULT 0,
    currency                VARCHAR(3)      NOT NULL DEFAULT 'INR',
    due_date                TIMESTAMPTZ,
    paid_at                 TIMESTAMPTZ,
    razorpay_invoice_id     VARCHAR(255),
    pdf_url                 VARCHAR(500),
    billing_period_start    TIMESTAMPTZ,
    billing_period_end      TIMESTAMPTZ
    );

CREATE INDEX IF NOT EXISTS idx_invoices_subscription_id ON invoices (subscription_id);
CREATE INDEX IF NOT EXISTS idx_invoices_status          ON invoices (status);
CREATE INDEX IF NOT EXISTS idx_invoices_invoice_number  ON invoices (invoice_number);
CREATE INDEX IF NOT EXISTS idx_invoices_due_date        ON invoices (due_date);
