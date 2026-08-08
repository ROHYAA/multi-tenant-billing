package com.mtbs.tenant.dto.tenant;

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
public class TenantSchemaInfoResponse {

    private String schemaName;
    private long userCount;
    private long roleCount;
    private Instant createdAt;
    private long subscriptionCount;
}