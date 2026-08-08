package com.mtbs.business.product.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
import java.time.Instant;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductResponse {
 
    private Long       id;
    private String     name;
    private String     description;
    private BigDecimal price;
    private BigDecimal taxPercentage;
    private String     hsnSacCode;
    private String     unit;
 
    /**
     * False = deactivated. Deactivated products cannot be added to new invoices
     * but remain visible in historical invoice line items.
     */
    private Boolean isActive;
 
    private Instant createdAt;
    private Instant updatedAt;
}