-- V1: Create tenants table in public schema
CREATE TABLE IF NOT EXISTS tenants (
    id              BIGSERIAL       PRIMARY KEY,
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT,
    name            VARCHAR(255)    NOT NULL,
    schema_name     VARCHAR(63)     NOT NULL UNIQUE,
    plan_id        BIGINT,
    status          VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE',
    owner_email     VARCHAR(255),
    slug            VARCHAR(63)     UNIQUE,
    onboarding_step INT             NOT NULL DEFAULT 0
    );

COMMENT ON COLUMN tenants.plan_id IS
  'FK to public.plans — denormalized for fast admin queries and limit checks. Source of truth for billing is subscription.plan_id. Set when tenant subscription is created. NULL until first plan is assigned.';

CREATE INDEX IF NOT EXISTS idx_tenants_schema_name ON tenants (schema_name);
CREATE INDEX IF NOT EXISTS idx_tenants_status      ON tenants (status);
CREATE INDEX IF NOT EXISTS idx_tenants_slug        ON tenants (slug) WHERE slug IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tenants_plan_id    ON tenants (plan_id) WHERE plan_id IS NOT NULL;