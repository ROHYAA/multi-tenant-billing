package com.mtbs.auth.dto.user;

import com.mtbs.shared.enums.auth.Status;
import jakarta.validation.constraints.NotNull;
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
public class ChangeUserStatusRequest {

    @NotNull(message = "Status is required")
    private Status status;
}