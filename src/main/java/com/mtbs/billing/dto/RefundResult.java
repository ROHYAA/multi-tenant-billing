package com.mtbs.billing.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundResult {

    private boolean success;
    private String refundId;
    private Long amountRefunded;
    private String failureReason;
}
