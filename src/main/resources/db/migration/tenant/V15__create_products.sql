-- ─────────────────────────────────────────────────────────────────────────────
-- V16 — Create products table (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Tenant's product/service catalog. Items from this table are referenced
-- (by snapshot, not live FK) when building business invoice line items.
--
-- price           → Base price in the tenant's default currency.
-- tax_percentage  → Default GST/tax rate for this product (e.g. 18.00 for 18%).
--                   Snapshotted onto each invoice line item at creation time
--                   so price changes don't retroactively affect old invoices.
-- hsn_sac_code    → Harmonized System Nomenclature / Services Accounting Code.
--                   Mandatory for GST-compliant invoices in India.
-- unit            → e.g. "hrs", "kg", "units", "license" — printed on invoice.
-- is_active       → Soft deactivation. Deactivated products cannot be added to
--                   new invoices but existing invoice items are unaffected.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE products (
                          id          BIGSERIAL       PRIMARY KEY,
                          deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
                          deleted_at  TIMESTAMPTZ,
                          version     BIGINT          NOT NULL DEFAULT 0,
                          created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                          updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                          created_by  BIGINT,
                          updated_by  BIGINT,

                          name            VARCHAR(255)    NOT NULL,
                          description     TEXT,
                          price           NUMERIC(12,2)   NOT NULL DEFAULT 0,
                          tax_percentage  NUMERIC(5,2)    NOT NULL DEFAULT 0,
                          hsn_sac_code    VARCHAR(20),
                          unit            VARCHAR(50),
                          is_active       BOOLEAN         NOT NULL DEFAULT TRUE
);

-- Active product search (catalog listing)
CREATE INDEX idx_products_name_active
    ON products(name)
    WHERE deleted = FALSE AND is_active = TRUE;

-- HSN/SAC code lookup (compliance reporting)
CREATE INDEX idx_products_hsn_sac
    ON products(hsn_sac_code)
    WHERE hsn_sac_code IS NOT NULL;