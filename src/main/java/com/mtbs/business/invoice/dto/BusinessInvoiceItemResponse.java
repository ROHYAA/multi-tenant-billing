package com.mtbs.business.invoice.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessInvoiceItemResponse {
 
    private Long       id;
 
    /**
     * Source product ID. Null if this was a free-text line item.
     */
    private Long       productId;
 
    /** Line item label as it appears on the PDF invoice. */
    private String     description;
 
    private BigDecimal quantity;
    private BigDecimal unitPrice;
 
    /** Tax rate snapshot — reflects the rate at invoice creation time. */
    private BigDecimal taxPercentage;
 
    /** Computed and stored: (unitPrice × quantity) × (taxPercentage / 100). */
    private BigDecimal taxAmount;
 
    /** Computed and stored: (unitPrice × quantity) + taxAmount. */
    private BigDecimal total;
}