package com.mtbs.business.invoice.dto;
 
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
import java.util.List;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CreateBusinessInvoiceRequest {
 
    @NotNull(message = "Customer ID is required")
    private Long customerId;
 
    /**
     * Currency code. Defaults to INR if not provided.
     */
    @Size(min = 3, max = 3, message = "Currency must be a 3-character ISO code")
    private String currency;
 
    /** Optional memo printed at the bottom of the PDF invoice. */
    private String notes;
 
    /**
     * Initial line items. At least one is recommended but not required —
     * items can be added after creation via POST /api/business-invoices/{id}/items
     * while the invoice is in DRAFT status.
     */
    @Valid
    @NotEmpty(message = "At least one line item is required")
    private List<InvoiceLineItemRequest> items;
 
    // ── Nested DTO ────────────────────────────────────────────────────────────
 
    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InvoiceLineItemRequest {
 
        /**
         * Optional product catalog ID.
         * If provided: description, unitPrice, and taxPercentage are
         * snapshotted from the product (any values sent here are ignored).
         * If null: description and unitPrice are required from the request.
         */
        private Long productId;
 
        /** Required when productId is null. Ignored when productId is set. */
        @Size(max = 500)
        private String description;
 
        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0.01", message = "Quantity must be greater than zero")
        private BigDecimal quantity;
 
        /**
         * Required when productId is null.
         * Ignored (overridden by product.price) when productId is set.
         */
        @DecimalMin(value = "0.00", message = "Unit price must be zero or greater")
        private BigDecimal unitPrice;
 
        /**
         * Tax percentage override. Optional.
         * Ignored when productId is set (product.taxPercentage is used).
         * Defaults to 0 when productId is null and not provided.
         */
        @DecimalMin(value = "0.00", message = "Tax percentage must be zero or greater")
        private BigDecimal taxPercentage;
    }
}