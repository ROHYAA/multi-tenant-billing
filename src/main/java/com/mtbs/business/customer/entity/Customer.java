package com.mtbs.business.customer.entity;

import com.mtbs.shared.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Tenant's end-customer — the person or business the tenant sends invoices to.
 *
 * NOT the same as auth.User (which is a team member of the tenant).
 *
 * Lives in the tenant schema — each tenant has its own isolated customer table.
 *
 * razorpayCustomerId is created via PaymentGatewayPort.createCustomer() during
 * customer creation and stored here for future payment link generation.
 *
 * gstin (GST Identification Number) is optional but required for GST-compliant
 * B2B invoices in India.
 */
@Entity
@Table(name = "customers")
@SQLDelete(sql = "UPDATE customers SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer extends AuditableEntity {

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String address;

    /**
     * GST Identification Number — 15-character alphanumeric.
     * Null for B2C customers or non-GST-registered businesses.
     * Printed on GST invoices when present.
     */
    @Column(name = "gstin", length = 20)
    private String gstin;

    /**
     * Razorpay customer ID (cust_XXXX).
     * Created via PaymentGatewayPort.createCustomer() during customer creation.
     * Null until the customer has been synced to Razorpay.
     */
    @Column(name = "razorpay_customer_id", length = 100)
    private String razorpayCustomerId;
}