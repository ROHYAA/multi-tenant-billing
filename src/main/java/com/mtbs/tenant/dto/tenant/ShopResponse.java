package com.mtbs.tenant.dto.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.auth.Status;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShopResponse {

    private Long id;
    private String name;
    private String schemaName;
    private Status status;
    private String planName;
    private Instant subscriptionExpiresAt;
    private Instant createdAt;
}