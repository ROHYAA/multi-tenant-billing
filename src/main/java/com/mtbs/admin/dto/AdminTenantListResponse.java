package com.mtbs.admin.dto;

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
public class AdminTenantListResponse {

    private Long id;
    private String name;
    private String schemaName;
    private Long planId;
    private String planCode;
    private String planName;
    private Status status;
    private long userCount;
    private Instant createdAt;
}