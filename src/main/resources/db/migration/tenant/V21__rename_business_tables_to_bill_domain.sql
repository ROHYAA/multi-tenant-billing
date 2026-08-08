-- ─────────────────────────────────────────────────────────────────────────────
-- V21 (tenant) — Rename retail-billing tables into ShopLedger's own vocabulary
-- ─────────────────────────────────────────────────────────────────────────────
-- business_invoices/business_invoice_items/business_payments only ever
-- existed to disambiguate from the platform-billing tables (invoices/
-- invoice_line_items/payments), which V20 just dropped. That name is now
-- free, so business_payments becomes plain "payments".
--
-- Postgres automatically re-points FK constraints, indexes, and sequences
-- to follow the renamed table — no separate FK/index maintenance needed.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE business_invoices RENAME TO bills;
ALTER TABLE business_invoice_items RENAME TO bill_items;
ALTER TABLE business_payments RENAME TO payments;

-- Drop the Razorpay online-payment-link columns — ShopLedger V1 ships with
-- cash/manual payment recording only (see app's Razorpay decision).
ALTER TABLE bills DROP COLUMN IF EXISTS razorpay_payment_link_id;
ALTER TABLE payments DROP COLUMN IF EXISTS razorpay_payment_link_id;
ALTER TABLE customers DROP COLUMN IF EXISTS razorpay_customer_id;
