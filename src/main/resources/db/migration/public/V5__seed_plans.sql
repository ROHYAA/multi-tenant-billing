-- V5: Seed plans (normalized structure with pricing, features, limits)
-- Idempotent: uses INSERT ... RETURNING with fallback SELECT to handle re-runs

DO $$
DECLARE
    free_id          BIGINT;
    pro_id           BIGINT;
    enterprise_id    BIGINT;
BEGIN

    -- ──────────────────────────────────────────────────────────────────────────
    -- 1. Insert FREE plan or retrieve if already exists
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plans (code, name, display_name, description, is_active, is_public, sort_order, badge,
                              created_at, updated_at, deleted, version)
        VALUES ('FREE', 'Free', 'Free', 
                'Get started with the basics. No credit card required.',
                TRUE, TRUE, 1, NULL,
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT DO NOTHING;
    
    SELECT id INTO free_id FROM public.plans WHERE code = 'FREE' AND deleted = FALSE;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 2. Insert PRO plan or retrieve if already exists
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plans (code, name, display_name, description, is_active, is_public, sort_order, badge,
                              created_at, updated_at, deleted, version)
        VALUES ('PRO', 'Pro', 'Pro',
                'For growing teams that need more power and flexibility.',
                TRUE, TRUE, 2, 'Most Popular',
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT DO NOTHING;
    
    SELECT id INTO pro_id FROM public.plans WHERE code = 'PRO' AND deleted = FALSE;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 3. Insert ENTERPRISE plan or retrieve if already exists
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plans (code, name, display_name, description, is_active, is_public, sort_order, badge,
                              created_at, updated_at, deleted, version)
        VALUES ('ENTERPRISE', 'Enterprise', 'Enterprise',
                'Unlimited everything. Dedicated support. Custom SLAs.',
                TRUE, TRUE, 3, NULL,
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT DO NOTHING;
    
    SELECT id INTO enterprise_id FROM public.plans WHERE code = 'ENTERPRISE' AND deleted = FALSE;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 4. FREE Plan Pricing (MONTHLY only, price=0)
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_pricing (plan_id, billing_cycle, price, currency, trial_days, is_default,
                                     created_at, updated_at, deleted, version)
        VALUES (free_id, 'MONTHLY', 0.00, 'INR', 0, TRUE,
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT (plan_id, billing_cycle) WHERE deleted = FALSE DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 5. PRO Plan Pricing (MONTHLY + ANNUAL)
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_pricing (plan_id, billing_cycle, price, currency, trial_days, is_default,
                                     created_at, updated_at, deleted, version)
        VALUES (pro_id, 'MONTHLY', 999.00, 'INR', 14, TRUE,
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT (plan_id, billing_cycle) WHERE deleted = FALSE DO NOTHING;

    INSERT INTO public.plan_pricing (plan_id, billing_cycle, price, currency, trial_days, is_default,
                                     created_at, updated_at, deleted, version)
        VALUES (pro_id, 'ANNUAL', 9999.00, 'INR', 14, FALSE,
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT (plan_id, billing_cycle) WHERE deleted = FALSE DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 6. ENTERPRISE Plan Pricing (MONTHLY + ANNUAL)
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_pricing (plan_id, billing_cycle, price, currency, trial_days, is_default,
                                     created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'MONTHLY', 4999.00, 'INR', 30, TRUE,
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT (plan_id, billing_cycle) WHERE deleted = FALSE DO NOTHING;

    INSERT INTO public.plan_pricing (plan_id, billing_cycle, price, currency, trial_days, is_default,
                                     created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'ANNUAL', 49999.00, 'INR', 30, FALSE,
                NOW(), NOW(), FALSE, 0)
        ON CONFLICT (plan_id, billing_cycle) WHERE deleted = FALSE DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 7. FREE Plan Features
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (free_id, 'API_ACCESS', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (free_id, 'PDF_EXPORT', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (free_id, 'ADVANCED_ANALYTICS', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (free_id, 'CUSTOM_DOMAIN', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (free_id, 'WHITE_LABEL', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (free_id, 'PRIORITY_SUPPORT', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 8. PRO Plan Features
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'API_ACCESS', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'PDF_EXPORT', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'ADVANCED_ANALYTICS', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'CUSTOM_DOMAIN', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'WHITE_LABEL', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'PRIORITY_SUPPORT', FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 9. ENTERPRISE Plan Features (all enabled)
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'API_ACCESS', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'PDF_EXPORT', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'ADVANCED_ANALYTICS', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'CUSTOM_DOMAIN', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'WHITE_LABEL', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_features (plan_id, feature_key, enabled, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'PRIORITY_SUPPORT', TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 10. FREE Plan Limits
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (free_id, 'ACTIVE_USERS', 3, FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (free_id, 'API_CALLS', 1000, FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (free_id, 'STORAGE_GB', 1, FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 11. PRO Plan Limits
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'ACTIVE_USERS', 25, FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'API_CALLS', 50000, FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (pro_id, 'STORAGE_GB', 20, FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;

    -- ──────────────────────────────────────────────────────────────────────────
    -- 12. ENTERPRISE Plan Limits (ACTIVE_USERS and API_CALLS unlimited, STORAGE_GB = 500)
    -- ──────────────────────────────────────────────────────────────────────────
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'ACTIVE_USERS', NULL, TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'API_CALLS', NULL, TRUE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;
    INSERT INTO public.plan_limits (plan_id, metric, value, unlimited, created_at, updated_at, deleted, version)
        VALUES (enterprise_id, 'STORAGE_GB', 500, FALSE, NOW(), NOW(), FALSE, 0) ON CONFLICT DO NOTHING;

END $$;