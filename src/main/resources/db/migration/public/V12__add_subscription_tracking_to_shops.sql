-- ─────────────────────────────────────────────────────────────────────────────
-- V12 — Offline-payment subscription tracking (plan label + expiry date) on
-- shops. Set by an admin on approve/reactivate; a daily job alerts 5 days
-- before expiry and auto-suspends on expiry until the admin reactivates.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE shops
    ADD COLUMN plan_name                VARCHAR(100),
    ADD COLUMN subscription_expires_at  TIMESTAMPTZ,
    -- Set once when the 5-day pre-expiry alert fires, so the daily job never
    -- re-sends it; reset to NULL whenever the plan is renewed (approve/reactivate).
    ADD COLUMN expiry_alert_sent_at     TIMESTAMPTZ;
