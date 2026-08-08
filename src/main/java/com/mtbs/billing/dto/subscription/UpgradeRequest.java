package com.mtbs.billing.dto.subscription;

import com.mtbs.shared.enums.billing.BillingCycle;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for POST /api/subscriptions/upgrade/pro
 * and POST /api/subscriptions/upgrade/enterprise.
 *
 * The target plan is encoded in the URL path — this body only carries
 * the billing cycle choice. This is intentional: it forces the frontend
 * to make an explicit HTTP call per plan rather than passing a planId
 * that could be tampered with (e.g. passing an ENTERPRISE planId to the
 * /upgrade/pro endpoint).
 *
 * BILLING CYCLE SEMANTICS:
 *   MONTHLY — charged every 30 days, priceMonthly from plans table
 *   ANNUAL  — charged every 365 days, priceAnnual from plans table
 *             Annual is always cheaper per-month; show savings in the UI
 *             using UpgradePreviewResponse.fullCyclePrice comparison.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpgradeRequest {

    @NotNull(message = "Billing cycle is required — MONTHLY or ANNUAL")
    private BillingCycle billingCycle;
}