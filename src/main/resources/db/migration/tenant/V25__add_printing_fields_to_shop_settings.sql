-- ─────────────────────────────────────────────────────────────────────────────
-- V25 — Add printing-related columns to shop_settings (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Phase 2.2 (bill printing system):
--   upi_id                  -> encoded into the dynamic UPI QR when showQrCode
--                              is true; QR is skipped (not drawn) when null.
--   signature_attachment_id -> references attachments(id) in this same schema.
--                              Falls back to a text-only signature line when
--                              null and showSignature is true.
--   watermark_text          -> presence alone enables the watermark (no
--                              separate boolean) — null/blank means no
--                              watermark drawn.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE shop_settings
    ADD COLUMN upi_id                  VARCHAR(100),
    ADD COLUMN signature_attachment_id BIGINT,
    ADD COLUMN watermark_text          VARCHAR(50);
