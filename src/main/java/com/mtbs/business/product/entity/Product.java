package com.mtbs.business.product.entity;

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

import java.math.BigDecimal;

/**
 * An item in the tenant's product/service catalog.
 *
 * price and taxPercentage represent the DEFAULT values for this product.
 * When a product is added to a BusinessInvoice, these values are SNAPSHOTTED
 * onto BusinessInvoiceItem — future changes to the product do NOT retroactively
 * affect existing invoices. This is a legal/audit requirement.
 *
 * hsnSacCode (Harmonized System Nomenclature / Services Accounting Code):
 * Required for GST-compliant invoices in India. Products = HSN, Services = SAC.
 *
 * isActive: Soft deactivation. Deactivated products cannot be added to new
 * invoices but remain visible on historical invoice line items.
 */
@Entity
@Table(name = "products")
@SQLDelete(sql = "UPDATE products SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product extends AuditableEntity {

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Base price per unit in the tenant's currency.
     * Snapshotted onto invoice line items at creation time.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    /**
     * Default GST / tax rate for this product (e.g. 18.00 = 18%).
     * Snapshotted onto invoice line items at creation time.
     * Zero = tax-exempt product.
     */
    @Column(name = "tax_percentage", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxPercentage = BigDecimal.ZERO;

    /**
     * HSN (goods) or SAC (services) code for GST compliance.
     * Printed on GST invoices. Null for non-Indian tenants.
     */
    @Column(name = "hsn_sac_code", length = 20)
    private String hsnSacCode;

    /**
     * Unit of measure printed on invoice: "hrs", "kg", "units", "license", etc.
     * Null = no unit label printed.
     */
    @Column(length = 50)
    private String unit;

    /**
     * False = deactivated. Deactivated products cannot be added to new invoices.
     * Use deactivation instead of deletion when a product is discontinued.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}