-- ─────────────────────────────────────────────────────────────────────────────
-- V18 — Create business_invoice_items table (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Individual line items on a business invoice.
-- Prices and tax rates are SNAPSHOTTED at invoice creation time — they do not
-- reflect future product price changes. This is critical for audit compliance.
--
-- product_id      → Nullable. If set, the item was sourced from the product
--                   catalog at creation time. If null, it was typed manually
--                   (free-text description, custom price).
--                   In either case, unit_price and tax_percentage are snapshot
--                   values — they do NOT change if the product is later updated.
-- description     → Line item label as it appears on the printed invoice.
--                   Always populated, even for catalog items.
-- quantity        → Supports decimals (e.g. 2.5 hours of consulting).
-- unit_price      → Price per unit, snapshotted from product.price at creation.
-- tax_percentage  → GST rate snapshotted from product.tax_percentage at creation.
-- tax_amount      → Computed: (unit_price × quantity) × (tax_percentage / 100).
--                   Stored explicitly for audit trail — not recomputed on read.
-- total           → Computed: (unit_price × quantity) + tax_amount.
--                   Stored explicitly for the same reason.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE business_invoice_items (
                                        id          BIGSERIAL       PRIMARY KEY,
                                        deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
                                        deleted_at  TIMESTAMPTZ,
                                        version     BIGINT          NOT NULL DEFAULT 0,
                                        created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                                        updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
                                        created_by  BIGINT,
                                        updated_by  BIGINT,

                                        invoice_id      BIGINT          NOT NULL    REFERENCES business_invoices(id),
                                        product_id      BIGINT,                 -- nullable: null = free-text line item
                                        description     VARCHAR(500)    NOT NULL,
                                        quantity        NUMERIC(10,2)   NOT NULL DEFAULT 1,
                                        unit_price      NUMERIC(12,2)   NOT NULL DEFAULT 0,
                                        tax_percentage  NUMERIC(5,2)    NOT NULL DEFAULT 0,
                                        tax_amount      NUMERIC(12,2)   NOT NULL DEFAULT 0,
                                        total           NUMERIC(12,2)   NOT NULL DEFAULT 0
);

-- Primary access pattern: all items for an invoice
CREATE INDEX idx_business_invoice_items_invoice
    ON business_invoice_items(invoice_id)
    WHERE deleted = FALSE;

-- Product usage reporting: which invoices reference this product?
CREATE INDEX idx_business_invoice_items_product
    ON business_invoice_items(product_id)
    WHERE product_id IS NOT NULL AND deleted = FALSE;