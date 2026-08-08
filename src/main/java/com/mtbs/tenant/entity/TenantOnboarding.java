package com.mtbs.tenant.entity;

import com.mtbs.shared.entity.AuditableEntity;
import com.mtbs.shared.enums.billing.BillingCycle;
import com.mtbs.tenant.enums.BusinessType;
import com.mtbs.tenant.enums.KycStatus;
import com.mtbs.tenant.enums.OnboardingPaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "tenant_onboarding", schema = "public")
@SQLDelete(sql = "UPDATE tenant_onboarding SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantOnboarding extends AuditableEntity {

    @Column(name = "tenant_id", nullable = false, unique = true)
    private Long tenantId;

    // ── Step 1 — Business details ─────────────────────────────────────────────

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "slug", length = 63, unique = true)
    private String slug;

    @Column(name = "industry", length = 100)
    private String industry;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "timezone", length = 100)
    private String timezone;

    @Column(name = "website", length = 255)
    private String website;

    @Column(name = "team_size", length = 50)
    private String teamSize;

    @Column(name = "use_case", length = 255)
    private String useCase;

    // ── Step 2 — KYC ─────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", length = 50)
    private BusinessType businessType;

    @Column(name = "registration_number", length = 100)
    private String registrationNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_address", columnDefinition = "jsonb")
    private Map<String, String> billingAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "registered_address", columnDefinition = "jsonb")
    private Map<String, String> registeredAddress;

    @Column(name = "kyc_document_ref", length = 500)
    private String kycDocumentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", length = 30, nullable = false)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    // ── Step 3 — Plan & billing ───────────────────────────────────────────────

    @Column(name = "selected_plan_id")
    private Long selectedPlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_billing_cycle", length = 20)
    private BillingCycle selectedBillingCycle;

    @Column(name = "razorpay_customer_id", length = 255)
    private String razorpayCustomerId;

    // ── Step 3 — Payment (for paid plans) ─────────────────────────────────────────

    @Column(name = "razorpay_order_id", length = 255)
    private String razorpayOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20)
    private OnboardingPaymentStatus paymentStatus;

    @Column(name = "payment_initiated_at")
    private Instant paymentInitiatedAt;

    // ── Completion ────────────────────────────────────────────────────────────

    @Column(name = "completed_at")
    private Instant completedAt;
}