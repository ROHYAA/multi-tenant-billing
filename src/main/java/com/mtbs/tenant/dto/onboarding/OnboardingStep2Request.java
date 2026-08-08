package com.mtbs.tenant.dto.onboarding;

import com.mtbs.tenant.enums.BusinessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
public class OnboardingStep2Request {

    @NotNull(message = "Business type is required")
    private BusinessType businessType;

    @Size(max = 100)
    private String registrationNumber;

    @Valid
    @NotNull(message = "Billing address is required")
    private AddressRequest billingAddress;

    @Valid
    private AddressRequest registeredAddress;

    /**
     * Dummy document reference string — e.g. "GST:29ABCDE1234F1Z5" or a UUID
     * pointing to a future file-storage record.
     */
    @Size(max = 500)
    private String kycDocumentRef;

    // ── Nested DTO ────────────────────────────────────────────────────────────

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddressRequest {

        @NotNull
        @Size(max = 255)
        private String line1;

        @Size(max = 255)
        private String line2;

        @NotNull
        @Size(max = 100)
        private String city;

        @NotNull
        @Size(max = 100)
        private String state;

        @NotNull
        @Size(max = 10)
        private String pincode;

        @NotNull
        @Size(max = 100)
        private String country;
    }
}