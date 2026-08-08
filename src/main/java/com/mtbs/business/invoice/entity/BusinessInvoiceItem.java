package com.mtbs.business.invoice.entity;

import com.mtbs.shared.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * A single line item on a BusinessInvoice.
 *
 * SNAPSHOT DESIGN — prices and tax rates are copied from the product at the
 * moment the line item is created. They are NOT live-linked. If a product's
 * price changes after invoice creation, existing line items are unaffected.
 * This is a legal requirement for invoice audit compliance.
 *
 * productId is nullable:
 *   - Not null → item was sourced from the product catalog (snapshot taken)
 *   - Null     → free-text line item (custom description + price, no catalog link)
 *
 * Computed fields (taxAmount, total) are stored explicitly — never computed
 * on read — so they remain accurate even if business logic changes.
 *
 * Computation:
 *   taxAmount = (unitPrice × quantity) × (taxPercentage / 100)
 *   total     = (unitPrice × quantity) + taxAmount
 */
@Entity
@Table(name = "business_invoice_items")
@SQLDelete(sql = "UPDATE business_invoice_items SET deleted = true, deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted = false")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessInvoiceItem extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private BusinessInvoice invoice;

    /**
     * Reference to the catalog product. Null for free-text line items.
     * Stored as Long — not a @ManyToOne — because deactivated/deleted products
     * must not cascade to historical invoice items.
     */
    @Column(name = "product_id")
    private Long productId;

    /** Line item label as printed on the invoice PDF. Always populated. */
    @Column(nullable = false, length = 500)
    private String description;

    /** Supports decimal quantities (e.g. 2.5 hours). */
    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ONE;

    /** Price per unit — snapshotted from product.price at creation time. */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    /** Tax rate — snapshotted from product.taxPercentage at creation time. */
    @Column(name = "tax_percentage", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal taxPercentage = BigDecimal.ZERO;

    /** Computed: (unitPrice × quantity) × (taxPercentage / 100). Stored. */
    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** Computed: (unitPrice × quantity) + taxAmount. Stored. */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;
}