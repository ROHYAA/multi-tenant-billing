package com.mtbs.auth.dto.user;

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
public class UserResponse {

    private Long id;
    private String name;
    private String email;
    private String roleName;
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
}