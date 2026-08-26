package com.mtbs.admin.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Used by both POST /admin/tenants/{id}/approve and .../reactivate — offline-payment plan tracking. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApproveTenantRequest {

    @NotBlank(message = "Plan name is required")
    private String planName;

    @NotNull(message = "Subscription expiry date is required")
    @Future(message = "Subscription expiry date must be in the future")
    private Instant subscriptionExpiresAt;
}
