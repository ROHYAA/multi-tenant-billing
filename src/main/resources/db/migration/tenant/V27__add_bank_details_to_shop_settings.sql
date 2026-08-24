-- ─────────────────────────────────────────────────────────────────────────────
-- V27 — Add bank-detail columns to shop_settings (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Printed in the new Marathi Cash Memo bill template's footer (and any future
-- template that wants a bank-details block). All optional — the footer block
-- is only drawn when bank_name is set.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE shop_settings
    ADD COLUMN bank_name       VARCHAR(255),
    ADD COLUMN bank_account_no VARCHAR(30),
    ADD COLUMN bank_ifsc       VARCHAR(11),
    ADD COLUMN bank_branch     VARCHAR(255);
