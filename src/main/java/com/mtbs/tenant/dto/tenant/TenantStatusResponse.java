package com.mtbs.tenant.dto.tenant;

import com.mtbs.shared.enums.auth.Status;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantStatusResponse {

    private boolean isSuspended;
    private Status tenantStatus;
}