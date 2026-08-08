package com.mtbs.tenant.service.onboarding;

import com.mtbs.tenant.dto.onboarding.OnboardingStep1Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStep2Request;
import com.mtbs.tenant.dto.onboarding.OnboardingStep3Request;
import com.mtbs.tenant.entity.TenantOnboarding;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.enums.KycStatus;
import com.mtbs.tenant.repository.TenantOnboardingRepository;
import com.mtbs.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Isolated Spring proxy bean for all @Transactional onboarding writes.
 * Called only by OnboardingService — never directly from controllers.
 * All operations touch the PUBLIC schema only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingScopedService {

    private final TenantOnboardingRepository onboardingRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public void persistStep1(TenantOnboarding onboarding,
                             OnboardingStep1Request request,
                             Tenant tenant) {

        onboarding.setCompanyName(request.getCompanyName());
        onboarding.setSlug(request.getSlug());
        onboarding.setIndustry(request.getIndustry());
        onboarding.setPhone(request.getPhone());
        onboarding.setTimezone(request.getTimezone());
        onboarding.setWebsite(request.getWebsite());
        onboarding.setTeamSize(request.getTeamSize());
        onboarding.setUseCase(request.getUseCase());
        onboardingRepository.save(onboarding);

        // Mirror company name and slug into the tenants row immediately
        tenant.setName(request.getCompanyName());
        tenant.setSlug(request.getSlug());
        if (tenant.getOnboardingStep() < 1) {
            tenant.setOnboardingStep(1);
        }
        tenantRepository.save(tenant);

        log.info("Step 1 persisted for tenantId={}", tenant.getId());
    }

    @Transactional
    public void persistStep2(TenantOnboarding onboarding, OnboardingStep2Request request) {

        onboarding.setBusinessType(request.getBusinessType());
        onboarding.setRegistrationNumber(request.getRegistrationNumber());
        onboarding.setBillingAddress(addressToMap(request.getBillingAddress()));

        if (request.getRegisteredAddress() != null) {
            onboarding.setRegisteredAddress(addressToMap(request.getRegisteredAddress()));
        }

        onboarding.setKycDocumentRef(request.getKycDocumentRef());
        onboarding.setKycStatus(KycStatus.SUBMITTED);
        onboardingRepository.save(onboarding);

        // Advance onboarding_step on tenant row
        advanceTenantStep(onboarding.getTenantId(), 2);

        log.info("Step 2 (KYC) persisted for tenantId={}", onboarding.getTenantId());
    }

    @Transactional
    public void persistStep3(TenantOnboarding onboarding, OnboardingStep3Request request) {

        onboarding.setSelectedPlanId(request.getPlanId());
        onboarding.setSelectedBillingCycle(request.getBillingCycle());
        onboardingRepository.save(onboarding);

        advanceTenantStep(onboarding.getTenantId(), 3);

        log.info("Step 3 (plan) persisted for tenantId={}", onboarding.getTenantId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void advanceTenantStep(Long tenantId, int step) {
        tenantRepository.findById(tenantId).ifPresent(t -> {
            if (t.getOnboardingStep() < step) {
                t.setOnboardingStep(step);
                tenantRepository.save(t);
            }
        });
    }

    private Map<String, String> addressToMap(OnboardingStep2Request.AddressRequest addr) {
        Map<String, String> m = new HashMap<>();
        m.put("line1",   addr.getLine1());
        m.put("line2",   addr.getLine2() != null ? addr.getLine2() : "");
        m.put("city",    addr.getCity());
        m.put("state",   addr.getState());
        m.put("pincode", addr.getPincode());
        m.put("country", addr.getCountry());
        return m;
    }
}