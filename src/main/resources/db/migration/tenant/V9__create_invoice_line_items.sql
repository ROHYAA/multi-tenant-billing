-- V9: Create invoice_line_items table in tenant schema
CREATE TABLE IF NOT EXISTS invoice_line_items (
    id              BIGSERIAL       PRIMARY KEY,
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT,
    invoice_id      BIGINT          NOT NULL REFERENCES invoices(id),
    description     VARCHAR(500)    NOT NULL,
    quantity        NUMERIC(10,2)   NOT NULL DEFAULT 1,
    unit_price      NUMERIC(12,2)   NOT NULL DEFAULT 0,
    total_price     NUMERIC(12,2)   NOT NULL DEFAULT 0,
    line_item_type  VARCHAR(50)     NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_invoice_line_items_invoice_id ON invoice_line_items (invoice_id);
