-- V7__add_tenant_onboarding.sql
CREATE TABLE IF NOT EXISTS tenant_onboarding (
    id                      BIGSERIAL       PRIMARY KEY,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at              TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by              BIGINT,
    updated_by              BIGINT,
    tenant_id               BIGINT          NOT NULL UNIQUE,
    -- Step 1: Business details
    company_name            VARCHAR(255),
    slug                    VARCHAR(63)     UNIQUE,
    industry                VARCHAR(100),
    phone                   VARCHAR(50),
    timezone                VARCHAR(100),
    website                 VARCHAR(500),
    team_size               VARCHAR(50),
    use_case                TEXT,
    -- Step 2: KYC
    business_type           VARCHAR(50),
    registration_number     VARCHAR(100),
    billing_address         JSONB,
    registered_address      JSONB,
    kyc_document_ref        VARCHAR(255),
    kyc_status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    -- Step 3: Plan selection
    selected_plan_id        BIGINT,
    selected_billing_cycle  VARCHAR(50),
    razorpay_customer_id    VARCHAR(100),
    -- Payment fields (for future payment flow)
    razorpay_order_id       VARCHAR(255),
    payment_status          VARCHAR(20),
    payment_initiated_at   TIMESTAMPTZ,
    -- Completion
    completed_at            TIMESTAMPTZ
    );

CREATE INDEX IF NOT EXISTS idx_tenant_onboarding_tenant_id  ON tenant_onboarding (tenant_id);
CREATE INDEX IF NOT EXISTS idx_tenant_onboarding_kyc_status ON tenant_onboarding (kyc_status);
CREATE INDEX IF NOT EXISTS idx_tenant_onboarding_razorpay_order_id ON tenant_onboarding (razorpay_order_id);
CREATE INDEX IF NOT EXISTS idx_tenant_onboarding_payment_status ON tenant_onboarding (payment_status);