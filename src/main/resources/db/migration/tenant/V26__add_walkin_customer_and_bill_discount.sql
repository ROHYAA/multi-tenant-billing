-- ─────────────────────────────────────────────────────────────────────────────
-- V26 — Walk-in customer (system record) + bill-level discount support
-- ─────────────────────────────────────────────────────────────────────────────
-- Walk-in Customer: every tenant schema gets exactly one system-protected
-- customer row, auto-selected by the Billing screen for walk-in/cash sales
-- that don't need a real customer record. Identified by is_walkin = true —
-- not by name, since customers.name has no uniqueness constraint (unlike
-- roles.name, which is how RoleService's SYSTEM_ROLES check works). Protected
-- from delete/rename in CustomerService.
--
-- discount_amount on bills: bill-level only (no line-item discounts, by
-- product decision). Grand total = subtotal - discount_amount + tax_amount.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE customers ADD COLUMN is_walkin BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO customers (name, is_walkin, deleted, version, created_at, updated_at)
SELECT 'Walk-in Customer', true, false, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE is_walkin = true);

ALTER TABLE bills ADD COLUMN discount_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
