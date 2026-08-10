-- ─────────────────────────────────────────────────────────────────────────────
-- V22 — Create shop_settings table (tenant schema) — singleton config row
-- ─────────────────────────────────────────────────────────────────────────────
-- Exactly one row per tenant schema, seeded below so ShopSettingsService
-- never has to null-check or lazily create it — mirrors how V12 seeds
-- default roles into every new schema.
--
-- bill_template_id references public.bill_templates(id) — no enforced
-- cross-schema FK constraint, same precedent as role_permissions.permission_id.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE shop_settings (
    id          BIGSERIAL       PRIMARY KEY,
    deleted     BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ,
    version     BIGINT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    updated_by  BIGINT,

    -- Business Information
    business_name           VARCHAR(255),
    logo_attachment_id       BIGINT,
    business_type            VARCHAR(30),
    address                  TEXT,
    city                     VARCHAR(100),
    state                    VARCHAR(100),
    pincode                  VARCHAR(10),
    mobile                   VARCHAR(20),
    email                    VARCHAR(255),
    gstin                    VARCHAR(15),
    pan                      VARCHAR(10),
    website                  VARCHAR(255),

    -- Invoice & Regional Settings
    currency                 VARCHAR(3)   NOT NULL DEFAULT 'INR',
    currency_symbol          VARCHAR(5)   NOT NULL DEFAULT '₹',
    decimal_precision        SMALLINT     NOT NULL DEFAULT 2,
    timezone                 VARCHAR(50)  NOT NULL DEFAULT 'Asia/Kolkata',
    language                 VARCHAR(10)  NOT NULL DEFAULT 'en-IN',
    date_format              VARCHAR(20)  NOT NULL DEFAULT 'dd/MM/yyyy',

    -- Bill Settings
    paper_size               VARCHAR(20)  NOT NULL DEFAULT 'A4',
    bill_template_id         BIGINT       NOT NULL,
    show_logo                BOOLEAN      NOT NULL DEFAULT TRUE,
    show_gst                 BOOLEAN      NOT NULL DEFAULT TRUE,
    show_qr_code             BOOLEAN      NOT NULL DEFAULT FALSE,
    show_customer_address    BOOLEAN      NOT NULL DEFAULT TRUE,
    show_amount_in_words     BOOLEAN      NOT NULL DEFAULT TRUE,
    show_signature           BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Footer Settings
    terms_and_conditions     TEXT,
    warranty_text            TEXT,
    footer_message           VARCHAR(500),

    -- Printer Settings
    thermal_width            SMALLINT,
    margin                   SMALLINT     NOT NULL DEFAULT 5,
    font_size                SMALLINT     NOT NULL DEFAULT 10
);

INSERT INTO shop_settings (bill_template_id)
SELECT id FROM public.bill_templates WHERE code = 'CASH_MEMO_V1';
