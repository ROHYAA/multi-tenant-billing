-- ─────────────────────────────────────────────────────────────────────────────
-- V17 — Create business_invoices table (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Manual invoices created by the tenant for their customers.
-- Deliberately separate from the platform invoices table (V9) which stores
-- subscription billing invoices (tenant pays the platform).
--
-- Lifecycle: DRAFT → OPEN (finalize) → PAID (payment recorded)
--                  → VOID (voided before payment)
--
-- invoice_number         → Format: BINV-{tenantId}-{YYYYMM}-{seq}
--                          "B" prefix distinguishes from platform INV-* numbers.
-- customer_id            → FK to customers(id) within this tenant schema.
-- status                 → Stored as VARCHAR, maps to InvoiceStatus enum.
--                          Reuses existing enum: DRAFT / OPEN / PAID / VOID.
-- subtotal               → Sum of (qty × unit_price) across all line items.
-- tax_amount             → Sum of per-item tax amounts.
-- total_amount           → subtotal + tax_amount.
-- notes                  → Free-text memo printed at the bottom of the PDF invoice.
-- due_date               → Set when invoice is finalized (+N days based on tenant config).
-- pdf_url                → S3/storage URL of the generated PDF. Null until generated.
-- razorpay_payment_link_id → Created on demand via Razorpay Payment Links API.
--                            Null until the tenant generates a payment link.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE business_invoices (
       id          BIGSERIAL       PRIMARY KEY,
       deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
       deleted_at  TIMESTAMPTZ,
       version     BIGINT          NOT NULL DEFAULT 0,
       created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
       updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
       created_by  BIGINT,
       updated_by  BIGINT,

       invoice_number              VARCHAR(50)     NOT NULL,
       customer_id                 BIGINT          NOT NULL    REFERENCES customers(id),
       status                      VARCHAR(30)     NOT NULL    DEFAULT 'DRAFT',

       subtotal                    NUMERIC(12,2)   NOT NULL    DEFAULT 0,
       tax_amount                  NUMERIC(12,2)   NOT NULL    DEFAULT 0,
       total_amount                NUMERIC(12,2)   NOT NULL    DEFAULT 0,
       currency                    VARCHAR(3)      NOT NULL    DEFAULT 'INR',

       notes                       TEXT,
       due_date                    TIMESTAMPTZ,
       paid_at                     TIMESTAMPTZ,
       pdf_url                     VARCHAR(500),
       razorpay_payment_link_id    VARCHAR(100),

       CONSTRAINT uq_business_invoice_number UNIQUE (invoice_number)
);

-- Most common queries: invoices for a customer
CREATE INDEX idx_business_invoices_customer
    ON business_invoices(customer_id)
    WHERE deleted = FALSE;

-- Status filtering: OPEN invoices, PAID invoices etc.
CREATE INDEX idx_business_invoices_status
    ON business_invoices(status)
    WHERE deleted = FALSE;

-- Overdue detection: OPEN invoices past due date
CREATE INDEX idx_business_invoices_due_date
    ON business_invoices(due_date)
    WHERE deleted = FALSE AND status = 'OPEN';

-- Created date for report ordering
CREATE INDEX idx_business_invoices_created_at
    ON business_invoices(created_at DESC)
    WHERE deleted = FALSE;