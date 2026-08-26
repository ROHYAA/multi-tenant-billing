-- ─────────────────────────────────────────────────────────────────────────────
-- V28 — Payment status (Pending/Confirmed/Cancelled) + FIFO payment grouping
-- ─────────────────────────────────────────────────────────────────────────────
-- status: every payment used to be implicitly settled the instant it was
-- inserted. Credit payments now stay PENDING (a promise, not collected cash)
-- until confirmPayment() flips them to CONFIRMED — only CONFIRMED payments
-- count toward an invoice's outstanding balance / "is it fully paid" check.
-- DEFAULT 'CONFIRMED' means every pre-existing row (including production
-- data already live before this migration) keeps behaving exactly as before,
-- with no backfill step needed.
--
-- payment_group_id: nullable — set only when a single customer-level FIFO
-- payment (see PaymentService.recordForCustomer) fans out across multiple
-- bills, so those resulting rows can be displayed/receipted together. A
-- plain single-invoice payment (the existing flow) leaves this null.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE payments
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    ADD COLUMN payment_group_id UUID;

CREATE INDEX idx_payments_status_pending ON payments(status) WHERE status = 'PENDING';
