-- ─────────────────────────────────────────────────────────────────────────────
-- V10 — Create bill_templates table (public schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Platform-wide catalog of bill layouts every shop can pick from. Every
-- tenant schema's shop_settings.bill_template_id references a row here
-- (no enforced cross-schema FK — same precedent as
-- tenant.role_permissions.permission_id -> public.permissions).
--
-- code bridges a catalog row to the Java BillTemplateRenderer that draws
-- the PDF for it (com.mtbs.business.invoice.template).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE bill_templates (
    id          BIGSERIAL       PRIMARY KEY,
    deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    updated_by  BIGINT,

    code        VARCHAR(50)     NOT NULL UNIQUE,
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(500),
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE
);

INSERT INTO bill_templates (code, name, description, is_active)
VALUES ('CASH_MEMO_V1', 'Simple Cash Memo', 'Clean A4 tax-invoice-style layout', TRUE);
