-- ═══════════════════════════════════════════════════════════════════════════════
-- V3: Create normalized plan structure (4 tables)
-- ═══════════════════════════════════════════════════════════════════════════════

-- ───────────────────────────────────────────────────────────────────────────────
-- 1. PLANS — Identity/metadata only
-- ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.plans (
    id              BIGSERIAL       PRIMARY KEY,
    code            VARCHAR(50)     NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    display_name    VARCHAR(100)    NOT NULL,
    description     TEXT,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_public       BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    badge           VARCHAR(50),

    -- ─────────────────── Audit & soft-delete ────────────────────────────────
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT
);

COMMENT ON TABLE public.plans
    IS 'Plans table (normalized): stores plan identity only. Pricing, features, and limits are in separate tables.';

COMMENT ON COLUMN public.plans.code
    IS 'Unique plan code (e.g., FREE, PRO, ENTERPRISE). Uses code, not enum, for flexibility.';

COMMENT ON COLUMN public.plans.is_active
    IS 'Plan is active and can be purchased. Use soft-delete (deleted=true) to archive old plans.';

COMMENT ON COLUMN public.plans.sort_order
    IS 'Display order on pricing page (lower numbers first).';

COMMENT ON COLUMN public.plans.badge
    IS 'Optional badge text (e.g., "Most Popular", "Best Value").';

CREATE UNIQUE INDEX IF NOT EXISTS idx_plans_code
    ON public.plans (code) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plans_is_active
    ON public.plans (is_active) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plans_deleted
    ON public.plans (deleted);

-- ───────────────────────────────────────────────────────────────────────────────
-- 2. PLAN_PRICING — Pricing per billing cycle
-- ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.plan_pricing (
    id              BIGSERIAL       PRIMARY KEY,
    plan_id         BIGINT          NOT NULL REFERENCES public.plans(id),
    billing_cycle   VARCHAR(20)     NOT NULL,  -- MONTHLY, ANNUAL (matches BillingCycle enum)
    price           DECIMAL(12, 2)  NOT NULL,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'INR',
    trial_days      INTEGER         NOT NULL DEFAULT 0,
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE,

    -- ─────────────────── Audit & soft-delete ────────────────────────────────
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT
);

COMMENT ON TABLE public.plan_pricing
    IS 'Pricing per billing cycle. Each plan has at least one pricing row (MONTHLY). FREE plans have price=0.';

COMMENT ON COLUMN public.plan_pricing.billing_cycle
    IS 'Billing cycle (MONTHLY or ANNUAL). Must match BillingCycle enum exactly.';

COMMENT ON COLUMN public.plan_pricing.price
    IS 'Price in the specified currency. Zero for free plans.';

COMMENT ON COLUMN public.plan_pricing.trial_days
    IS 'Trial period in days. Zero means no trial. Applies to this pricing cycle.';

COMMENT ON COLUMN public.plan_pricing.is_default
    IS 'If true, this pricing is shown by default on the pricing UI for this plan.';

CREATE UNIQUE INDEX IF NOT EXISTS idx_plan_pricing_plan_cycle
    ON public.plan_pricing (plan_id, billing_cycle) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plan_pricing_plan_id
    ON public.plan_pricing (plan_id) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plan_pricing_deleted
    ON public.plan_pricing (deleted);

-- ───────────────────────────────────────────────────────────────────────────────
-- 3. PLAN_FEATURES — Feature flags per plan
-- ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.plan_features (
    id              BIGSERIAL       PRIMARY KEY,
    plan_id         BIGINT          NOT NULL REFERENCES public.plans(id),
    feature_key     VARCHAR(100)    NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,

    -- ─────────────────── Audit & soft-delete ────────────────────────────────
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT
);

COMMENT ON TABLE public.plan_features
    IS 'Feature flags per plan. One row per feature per plan.';

COMMENT ON COLUMN public.plan_features.feature_key
    IS 'Feature identifier (e.g., API_ACCESS, PDF_EXPORT, ADVANCED_ANALYTICS). Matches application feature gate keys.';

COMMENT ON COLUMN public.plan_features.enabled
    IS 'If true, this feature is available on this plan. If false, feature is not available.';

CREATE UNIQUE INDEX IF NOT EXISTS idx_plan_features_plan_key
    ON public.plan_features (plan_id, feature_key) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plan_features_plan_id
    ON public.plan_features (plan_id) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plan_features_deleted
    ON public.plan_features (deleted);

-- ───────────────────────────────────────────────────────────────────────────────
-- 4. PLAN_LIMITS — Usage limits per metric per plan
-- ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.plan_limits (
    id              BIGSERIAL       PRIMARY KEY,
    plan_id         BIGINT          NOT NULL REFERENCES public.plans(id),
    metric          VARCHAR(50)     NOT NULL,  -- ACTIVE_USERS, API_CALLS, STORAGE_GB (matches UsageMetric enum)
    value           BIGINT,                    -- NULL when unlimited = TRUE
    unlimited       BOOLEAN         NOT NULL DEFAULT FALSE,

    -- ─────────────────── Audit & soft-delete ────────────────────────────────
    deleted         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    updated_by      BIGINT
);

COMMENT ON TABLE public.plan_limits
    IS 'Usage limits per metric per plan. One row per metric per plan. Replaces flat max_users, max_api_calls, max_storage columns.';

COMMENT ON COLUMN public.plan_limits.metric
    IS 'Metric being limited (ACTIVE_USERS, API_CALLS, STORAGE_GB). Must match UsageMetric enum exactly.';

COMMENT ON COLUMN public.plan_limits.value
    IS 'Limit value. NULL when unlimited=true. Never use -1 for unlimited — use unlimited flag instead.';

COMMENT ON COLUMN public.plan_limits.unlimited
    IS 'If true, the limit is unlimited (value is ignored and should be NULL). If false, value enforces the limit.';

CREATE UNIQUE INDEX IF NOT EXISTS idx_plan_limits_plan_metric
    ON public.plan_limits (plan_id, metric) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plan_limits_plan_id
    ON public.plan_limits (plan_id) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_plan_limits_deleted
    ON public.plan_limits (deleted);

-- ───────────────────────────────────────────────────────────────────────────────
-- FK from tenants.plan_id to plans (after plans table exists)
-- Using DO $$ block for idempotent constraint addition
-- ───────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'fk_tenants_plan_id'
    ) THEN
        ALTER TABLE public.tenants
          ADD CONSTRAINT fk_tenants_plan_id
          FOREIGN KEY (plan_id) REFERENCES public.plans(id);
    END IF;
END $$;

COMMENT ON COLUMN public.tenants.plan_id IS
  'FK to public.plans — denormalized for fast admin queries and limit checks. Source of truth for billing is subscription.plan_id. Set when tenant subscription is created. NULL until first plan is assigned.';