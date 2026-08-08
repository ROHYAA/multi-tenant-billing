-- ─────────────────────────────────────────────────────────────────────────────
-- V20 (tenant) — Drop the platform-billing (SaaS subscription) tables
-- ─────────────────────────────────────────────────────────────────────────────
-- The subscription/invoice/payment/usage tables tracked what a shop owed the
-- MTBS platform for its own subscription — archived into legacy.saasbilling
-- Java code and no longer needed. The shop's own retail bills/payments
-- (business_invoices, business_invoice_items, business_payments) are
-- untouched here — renamed separately in V21.
--
-- FK-safe drop order: dependents before the tables they reference.
-- ─────────────────────────────────────────────────────────────────────────────

DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS invoice_line_items;
DROP TABLE IF EXISTS invoices;
DROP TABLE IF EXISTS usage_summaries;
DROP TABLE IF EXISTS usage_records;
DROP TABLE IF EXISTS subscriptions;
