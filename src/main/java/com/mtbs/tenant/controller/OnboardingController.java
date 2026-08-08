package com.mtbs.tenant.controller;

import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.tenant.dto.onboarding.OnboardingStatusResponse;
import com.mtbs.tenant.dto.onboarding.OnboardingStep1Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStep2Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStep3PaymentResponse;
import com.mtbs.tenant.dto.onboarding.OnboardingStep3Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStepResponse;
import com.mtbs.tenant.service.onboarding.OnboardingService;
import com.mtbs.shared.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/onboarding")
@RequiredArgsConstructor
@Tag(name = "Onboarding", description = "Tenant onboarding wizard — steps 1 through 3")
@SecurityRequirement(name = "bearerAuth")
public class OnboardingController {

    private final OnboardingService onboardingService;

    // ── GET /api/onboarding/status ────────────────────────────────────────────

    @GetMapping("/status")
    @Operation(
        summary = "Get onboarding status",
        description = "Returns current step, completed steps, and saved data for each completed step. " +
                      "Use this on app load to resume the wizard at the correct step."
    )
    public ResponseEntity<ApiResponse<OnboardingStatusResponse>> getStatus() {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        OnboardingStatusResponse response = onboardingService.getStatus(tenantId);
        return ResponseEntity.ok(ApiResponse.success(response, "Onboarding status fetched"));
    }

    // ── PUT /api/onboarding/step/1 ────────────────────────────────────────────

    @PutMapping("/step/1")
    @Operation(
        summary = "Save step 1 — business details",
        description = "Saves company name, slug, industry, contact, team size, and use-case. " +
                      "Idempotent — can be called multiple times to update saved values."
    )
    public ResponseEntity<ApiResponse<OnboardingStepResponse>> saveStep1(
            @Valid @RequestBody OnboardingStep1Request request) {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        OnboardingStepResponse response = onboardingService.saveStep1(tenantId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Step 1 saved"));
    }

    // ── PUT /api/onboarding/step/2 ────────────────────────────────────────────

    @PutMapping("/step/2")
    @Operation(
        summary = "Save step 2 — KYC",
        description = "Saves business type, registration number, billing/registered address, " +
                      "and document reference. Sets kyc_status = SUBMITTED. Requires step 1 to be done first."
    )
    public ResponseEntity<ApiResponse<OnboardingStepResponse>> saveStep2(
            @Valid @RequestBody OnboardingStep2Request request) {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        OnboardingStepResponse response = onboardingService.saveStep2(tenantId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Step 2 saved"));
    }

    // ── PUT /api/onboarding/step/3 ────────────────────────────────────────────

    @PutMapping("/step/3")
    @Operation(
        summary = "Save step 3 — plan and billing",
        description = "Selects plan and billing cycle. " +
                      "For FREE plan: completes onboarding immediately. " +
                      "For PAID plans (PRO/ENTERPRISE): creates Razorpay order and returns payment URL. " +
                      "Payment webhook completes onboarding on success. " +
                      "Requires step 2 (KYC submitted) to be done first."
    )
    public ResponseEntity<ApiResponse<OnboardingStep3PaymentResponse>> saveStep3(
            @Valid @RequestBody OnboardingStep3Request request) {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        OnboardingStep3PaymentResponse response = onboardingService.saveStep3(tenantId, request);
        
        String message = response.isPaymentRequired() 
                ? "Proceed to payment to complete onboarding" 
                : "Onboarding complete. Welcome!";
        
        return ResponseEntity.ok(ApiResponse.success(response, message));
    }
}