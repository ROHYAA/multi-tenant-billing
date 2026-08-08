package com.mtbs.billing.dto.subscription;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelSubscriptionRequest {

    @Builder.Default
    private boolean atPeriodEnd = true;
}
