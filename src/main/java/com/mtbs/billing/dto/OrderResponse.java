package com.mtbs.billing.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private String orderId; // Razorpay order_id (order_XXXXXXXXXX)
    private long amount; // in paise
    private String currency;
    private String receipt; // our idempotency key
    private String status;
    private String keyId; // Razorpay key_id â€” frontend needs this for Razorpay Checkout init
}
