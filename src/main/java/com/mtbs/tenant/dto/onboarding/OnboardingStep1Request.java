package com.mtbs.tenant.dto.onboarding;

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
public class OnboardingStep1Request {

    @NotBlank(message = "Company name is required")
    @Size(max = 255)
    private String companyName;

    @NotBlank(message = "Slug is required")
    @Size(min = 3, max = 63, message = "Slug must be 3–63 characters")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug may only contain lowercase letters, numbers, and hyphens")
    private String slug;

    @Size(max = 100)
    private String industry;

    @Size(max = 30)
    private String phone;

    @Size(max = 100)
    private String timezone;

    @Size(max = 255)
    private String website;

    /**
     * e.g. "1-10", "11-50", "51-200", "201-500", "500+"
     */
    @Size(max = 50)
    private String teamSize;

    @Size(max = 255)
    private String useCase;
}