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
public class AdminUserSearchResponse {

    private Long globalUserId;
    private Long tenantId;
    private String tenantName;
    private String userName;
    private String userEmail;
    private String roleName;
    private Status status;
    private Instant lastLoginAt;
}