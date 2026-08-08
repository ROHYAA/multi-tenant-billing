-- ─────────────────────────────────────────────────────────────────────────────
-- V9 (public) — Archive platform-billing tables, rename tenants → shops
-- ─────────────────────────────────────────────────────────────────────────────
-- Per the ShopLedger migration: the platform-billing (SaaS subscription)
-- module has been archived into legacy.saasbilling Java code — its tables
-- are no longer needed by the active application and are dropped here.
--
-- V1–V8 are NOT modified — this is a new, appended migration, per Flyway
-- best practice (never edit shipped migrations).
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Drop the FK from tenants → plans before dropping the plans tables.
ALTER TABLE public.tenants DROP CONSTRAINT IF EXISTS fk_tenants_plan_id;

-- 2. Drop the plan tables (FK-safe order: children before parent).
DROP TABLE IF EXISTS public.plan_pricing;
DROP TABLE IF EXISTS public.plan_features;
DROP TABLE IF EXISTS public.plan_limits;
DROP TABLE IF EXISTS public.plans;

-- 3. Drop the tenant onboarding wizard table (KYC + plan-selection + payment).
DROP TABLE IF EXISTS public.tenant_onboarding;

-- 4. Drop the now-orphaned columns on tenants.
ALTER TABLE public.tenants DROP COLUMN IF EXISTS plan_id;
ALTER TABLE public.tenants DROP COLUMN IF EXISTS onboarding_step;

-- 5. Rename tenants → shops (domain-facing rename; the schema-per-tenant
--    multitenancy machinery itself is untouched — see the app's rename-scope
--    decision. This table stores the ShopLedger business/shop record.)
ALTER TABLE public.tenants RENAME TO shops;

-- Indexes keep their prior names (idx_tenants_*) — renaming a table does not
-- require renaming its indexes, and Postgres updates the underlying object
-- references automatically.
