-- ─────────────────────────────────────────────────────────────────────────────
-- V19 — Create business_payments table (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Records payments received from customers against business invoices.
-- Multiple partial payments are supported per invoice.
-- The invoice is marked PAID when sum(business_payments.amount) >= invoice.total_amount.
--
-- method                   → Maps to PaymentMethod enum (CARD, UPI, NETBANKING,
--                            BANK_TRANSFER). Stored as VARCHAR.
--                            CASH is intentionally excluded — use BANK_TRANSFER
--                            for physical cash entries with a note.
-- notes                    → Free-text reference e.g. "UTR 123456789", "Cheque #42".
-- paid_at                  → Actual payment date (may differ from created_at —
--                            tenant can back-date a payment recorded offline).
-- razorpay_payment_link_id → The Razorpay Payment Link used by the customer
--                            to make this payment. Null for offline payments.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE business_payments (
       id          BIGSERIAL       PRIMARY KEY,
       deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
       deleted_at  TIMESTAMPTZ,
       version     BIGINT          NOT NULL DEFAULT 0,
       created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
       updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
       created_by  BIGINT,
       updated_by  BIGINT,

       invoice_id                  BIGINT          NOT NULL    REFERENCES business_invoices(id),
       amount                      NUMERIC(12,2)   NOT NULL,
       currency                    VARCHAR(3)      NOT NULL    DEFAULT 'INR',
       method                      VARCHAR(50)     NOT NULL,
       notes                       TEXT,
       paid_at                     TIMESTAMPTZ     NOT NULL,
       razorpay_payment_link_id    VARCHAR(100)
);

-- Primary access: all payments for an invoice
CREATE INDEX idx_business_payments_invoice
    ON business_payments(invoice_id)
    WHERE deleted = FALSE;

-- Date-range reporting: payments collected in a period
CREATE INDEX idx_business_payments_paid_at
    ON business_payments(paid_at DESC)
    WHERE deleted = FALSE;

-- Method breakdown for revenue reports
CREATE INDEX idx_business_payments_method
    ON business_payments(method)
    WHERE deleted = FALSE;