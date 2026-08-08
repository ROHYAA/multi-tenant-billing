package com.mtbs.business.invoice.dto;
 
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
 
/**
 * Request to add a single line item to an existing DRAFT invoice.
 * Same semantics as CreateBusinessInvoiceRequest.InvoiceLineItemRequest.
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AddLineItemRequest {
 
    private Long productId;
 
    @Size(max = 500)
    private String description;
 
    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.01", message = "Quantity must be greater than zero")
    private BigDecimal quantity;
 
    @DecimalMin(value = "0.00", message = "Unit price must be zero or greater")
    private BigDecimal unitPrice;
 
    @DecimalMin(value = "0.00", message = "Tax percentage must be zero or greater")
    private BigDecimal taxPercentage;
}