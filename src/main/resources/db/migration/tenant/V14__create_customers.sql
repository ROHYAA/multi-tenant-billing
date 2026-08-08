-- ─────────────────────────────────────────────────────────────────────────────
-- V15 — Create customers table (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Stores the tenant's end-customers (the people they bill).
-- Completely separate from the auth.users table which holds the tenant's
-- own team members.
--
-- gstin               → GST Identification Number (India). Nullable.
--                       Stored for inclusion on GST invoices.
-- razorpay_customer_id → Created via Razorpay API on customer creation.
--                        Used to generate payment links targeted to the customer.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE customers (
    -- Standard audit columns (mirrors AuditableEntity)
                           id          BIGSERIAL       PRIMARY KEY,
                           deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
                           deleted_at  TIMESTAMPTZ,
                           version     BIGINT          NOT NULL DEFAULT 0,
                           created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                           updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                           created_by  BIGINT,
                           updated_by  BIGINT,

    -- Business fields
                           name                    VARCHAR(255)    NOT NULL,
                           email                   VARCHAR(255),
                           phone                   VARCHAR(50),
                           address                 TEXT,
                           gstin                   VARCHAR(20),
                           razorpay_customer_id    VARCHAR(100)
);

-- Fast lookup by email (search + uniqueness check within tenant)
CREATE INDEX idx_customers_email
    ON customers(email)
    WHERE deleted = FALSE;

-- Fast lookup by name prefix (search autocomplete)
CREATE INDEX idx_customers_name
    ON customers(name)
    WHERE deleted = FALSE;

-- Razorpay ID lookup (avoid duplicates on sync)
CREATE INDEX idx_customers_razorpay_id
    ON customers(razorpay_customer_id)
    WHERE razorpay_customer_id IS NOT NULL;