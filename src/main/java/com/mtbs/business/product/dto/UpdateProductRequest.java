package com.mtbs.business.product.dto;
 
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.math.BigDecimal;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdateProductRequest {
 
    @Size(max = 255)
    private String name;
 
    private String description;
 
    @DecimalMin(value = "0.00", message = "Price must be zero or greater")
    private BigDecimal price;
 
    @DecimalMin(value = "0.00", message = "Tax percentage must be zero or greater")
    private BigDecimal taxPercentage;
 
    @Size(max = 20)
    private String hsnSacCode;
 
    @Size(max = 50)
    private String unit;
}