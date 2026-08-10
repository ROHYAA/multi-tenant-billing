-- ─────────────────────────────────────────────────────────────────────────────
-- V23 — Create attachments table (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Uploaded files belonging to this shop (logo today; signature/stamp/
-- QR-code images once those features exist). storage_key is opaque —
-- only the active StoragePort adapter interprets it.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE attachments (
    id          BIGSERIAL       PRIMARY KEY,
    deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    updated_by  BIGINT,

    purpose      VARCHAR(30)    NOT NULL,
    file_name    VARCHAR(255)   NOT NULL,
    content_type VARCHAR(100)   NOT NULL,
    size_bytes   BIGINT         NOT NULL,
    storage_key  VARCHAR(500)   NOT NULL
);

CREATE INDEX idx_attachments_purpose ON attachments(purpose) WHERE deleted = FALSE;
