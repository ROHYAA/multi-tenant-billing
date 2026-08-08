package com.mtbs.tenant.dto.tenant;

import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantStatusResponse {

    private String planName;
    private boolean isSuspended;
    private Status tenantStatus;
    private SubscriptionStatus subscriptionStatus;
    private Instant currentPeriodEnd;
    private Instant trialEndsAt;
}