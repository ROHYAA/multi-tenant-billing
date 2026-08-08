package com.mtbs.auth.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class RefreshTokenRequest {

    private String refreshToken;

    @NotBlank(message = "Tenant identifier is required")
    @Size(min = 2, max = 50, message = "Tenant slug must be 2-50 characters")
    @Pattern(
        regexp = "^[a-z0-9-]+$",
        message = "Tenant slug must contain only lowercase letters, numbers, and hyphens"
    )
    private String tenantSlug;
}