package com.mtbs.business.customer.dto;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
 
import java.time.Instant;
 
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerResponse {
 
    private Long   id;
    private String name;
    private String email;
    private String phone;
    private String address;
    private String gstin;
 
    /**
     * Razorpay customer ID (cust_XXXX).
     * Null if the customer has not yet been synced to Razorpay.
     */
    private String razorpayCustomerId;
 
    private Instant createdAt;
    private Instant updatedAt;
}