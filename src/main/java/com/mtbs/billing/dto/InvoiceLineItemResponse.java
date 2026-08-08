package com.mtbs.billing.dto;

import com.mtbs.shared.enums.billing.LineItemType;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceLineItemResponse {

    private Long id;
    private String description;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private LineItemType lineItemType;
}
