package com.mtbs.tenant.repository;

import com.mtbs.tenant.entity.TenantOnboarding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantOnboardingRepository extends JpaRepository<TenantOnboarding, Long> {

    Optional<TenantOnboarding> findByTenantId(Long tenantId);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndTenantIdNot(String slug, Long tenantId);

    Optional<TenantOnboarding> findByRazorpayOrderId(String razorpayOrderId);
}