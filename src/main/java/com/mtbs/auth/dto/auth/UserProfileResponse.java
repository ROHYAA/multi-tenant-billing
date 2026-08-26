package com.mtbs.auth.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mtbs.shared.enums.auth.Status;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserProfileResponse {

    private Long userId;
    private String name;
    private String email;
    private String role;
    private Status status;
    private Long tenantId;
    private String tenantName;
    /** The SHOP's status (ACTIVE/PENDING_APPROVAL/SUSPENDED/etc.), not the user's — drives the Shell's status banner. */
    private Status tenantStatus;
    /** Offline-payment plan tracking — drives the Shell's expiry-reminder banner. */
    private String planName;
    private Instant subscriptionExpiresAt;
    private String schemaName;
    private List<String> permissions;
    private Instant createdAt;
}