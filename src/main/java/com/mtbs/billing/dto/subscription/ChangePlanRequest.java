package com.mtbs.billing.dto.subscription;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePlanRequest {

    @NotNull(message = "New plan ID is required")
    private Long newPlanId;
}
