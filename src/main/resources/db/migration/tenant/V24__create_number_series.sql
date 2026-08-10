-- ─────────────────────────────────────────────────────────────────────────────
-- V24 — Create number_series table (tenant schema)
-- ─────────────────────────────────────────────────────────────────────────────
-- Document numbering state (INVOICE for V1). Replaces the hardcoded
-- "BINV-{tenantId}-{yearMonth}-{seq}" scheme BillService used to compute
-- from a live COUNT(*) — that approach both ignored shop-configured
-- numbering preferences and had a race condition under concurrent bill
-- creation. current_number is only ever updated via a row-locked
-- read-increment-write in NumberSeriesService, never here.
--
-- No soft-delete columns — series rows are permanent system state, never
-- removed.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE number_series (
    id          BIGSERIAL       PRIMARY KEY,
    deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    updated_by  BIGINT,

    series_type            VARCHAR(30)  NOT NULL,
    prefix                  VARCHAR(20)  NOT NULL,
    financial_year_format   VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    starting_number         BIGINT       NOT NULL DEFAULT 1,
    current_number          BIGINT       NOT NULL DEFAULT 0,
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE
);

-- At most one ACTIVE series per type — inactive/historical rows for the
-- same type are allowed to coexist (e.g. a shop retiring an old prefix).
CREATE UNIQUE INDEX uq_number_series_active_type
    ON number_series(series_type)
    WHERE is_active = TRUE;

INSERT INTO number_series (series_type, prefix, financial_year_format, starting_number, current_number, is_active)
VALUES ('INVOICE', 'INV', 'NONE', 1, 0, TRUE);
