-- V5: Create subscriptions table in tenant schema

CREATE TABLE IF NOT EXISTS subscriptions (

    -- Base fields
    id                              BIGSERIAL       PRIMARY KEY,
    deleted                         BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at                      TIMESTAMPTZ,
    version                         BIGINT          NOT NULL DEFAULT 0,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by                      BIGINT,
    updated_by                      BIGINT,

    -- Core subscription
    plan_id                         BIGINT          NOT NULL,
    status                          VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE',
    billing_cycle                   VARCHAR(50),

    -- Trial
    trial_start                     TIMESTAMPTZ,
    trial_end                       TIMESTAMPTZ,

    -- Billing period
    current_period_start            TIMESTAMPTZ,
    current_period_end              TIMESTAMPTZ,

    -- Cancellation
    cancelled_at                    TIMESTAMPTZ,
    cancel_at_period_end            BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Razorpay
    razorpay_subscription_id        VARCHAR(255),
    razorpay_customer_id            VARCHAR(255),

    -- ─────────────────────────────────────────────────────────────────────────
    -- Pending upgrade state
    -- Set when user initiates upgrade (POST /upgrade/pro or /upgrade/enterprise)
    -- Cleared when payment is verified or invoice is voided (abandoned checkout)
    -- All three columns are always set/cleared together atomically
    -- ─────────────────────────────────────────────────────────────────────────

    -- ID of the OPEN invoice that must be paid to activate the upgrade.
    -- planId is NOT changed until this invoice is marked PAID.
    upgrade_pending_invoice_id      BIGINT          DEFAULT NULL,

    -- Target plan for the pending upgrade (separate from plan_id).
    upgrade_pending_plan_id         BIGINT          DEFAULT NULL,

    -- Razorpay order ID — lets frontend re-open abandoned checkout without
    -- creating a duplicate order.
    upgrade_pending_razorpay_order_id VARCHAR(64)   DEFAULT NULL,

    -- ─────────────────────────────────────────────────────────────────────────
    -- Scheduled changes — take effect at the start of next billing period.
    -- Processed by SubscriptionExpiryJob / BillingCycleJob at renewal.
    -- ─────────────────────────────────────────────────────────────────────────

    -- Billing cycle to switch to at next renewal (ANNUAL → MONTHLY only).
    -- MONTHLY → ANNUAL requires payment so it takes immediate effect after pay.
    -- NULL = no cycle change scheduled.
    scheduled_billing_cycle         VARCHAR(20)     DEFAULT NULL,

    -- Plan to downgrade to at period end (always FREE plan ID in current impl).
    -- Set by POST /api/subscriptions/downgrade/free with atPeriodEnd = true.
    -- NULL = no downgrade scheduled.
    scheduled_downgrade_plan_id     BIGINT          DEFAULT NULL,

    -- Optional user-provided reason for the scheduled downgrade.
    -- Stored for audit trail and included in PLAN_DOWNGRADED notification email.
    downgrade_reason                VARCHAR(500)    DEFAULT NULL

    );

-- ─────────────────────────────────────────────────────────────────────────────
-- Standard operational indexes
-- ─────────────────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_subscriptions_status
    ON subscriptions (status);

CREATE INDEX IF NOT EXISTS idx_subscriptions_plan_id
    ON subscriptions (plan_id);

CREATE INDEX IF NOT EXISTS idx_subscriptions_current_period_end
    ON subscriptions (current_period_end);

-- ─────────────────────────────────────────────────────────────────────────────
-- Partial indexes for scheduler queries
-- Only index rows where these columns are non-null (usually a tiny fraction
-- of the table) — avoids bloating the index for the common NULL case.
-- ─────────────────────────────────────────────────────────────────────────────

-- SubscriptionExpiryJob: find subscriptions with a pending downgrade whose
-- period has expired.
CREATE INDEX IF NOT EXISTS idx_subscriptions_scheduled_downgrade
    ON subscriptions (scheduled_downgrade_plan_id)
    WHERE scheduled_downgrade_plan_id IS NOT NULL
    AND deleted = FALSE;

-- PaymentService: after verify, find the subscription linked to a paid invoice.
CREATE INDEX IF NOT EXISTS idx_subscriptions_upgrade_pending_invoice
    ON subscriptions (upgrade_pending_invoice_id)
    WHERE upgrade_pending_invoice_id IS NOT NULL
    AND deleted = FALSE;