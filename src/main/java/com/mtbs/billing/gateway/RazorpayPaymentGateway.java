package com.mtbs.billing.gateway;

import com.mtbs.billing.dto.OrderResponse;
import com.mtbs.billing.dto.RefundResult;
import com.mtbs.shared.exception.PaymentException;
import com.mtbs.shared.exception.ErrorCode;
import com.razorpay.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RazorpayPaymentGateway implements PaymentGatewayPort {

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    private RazorpayClient razorpayClient;

    @PostConstruct
    public void init() {
        try {
            this.razorpayClient = new RazorpayClient(keyId, keySecret);
            log.info("Razorpay client initialized with key: {}****", keyId.substring(0, Math.min(12, keyId.length())));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Failed to initialize Razorpay client", e);
        }
    }

    @Override
    public OrderResponse createOrder(long amountPaise, String currency, String receipt) {
        try {
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountPaise);
            orderRequest.put("currency", currency);
            orderRequest.put("receipt", receipt);
            orderRequest.put("payment_capture", 1); // Auto-capture

            Order order = razorpayClient.orders.create(orderRequest);

            return OrderResponse.builder()
                    .orderId(order.get("id"))
                    .amount(((Number) order.get("amount")).longValue())
                    .currency(order.get("currency"))
                    .receipt(order.get("receipt"))
                    .status(order.get("status"))
                    .keyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay error creating order: {}", e.getMessage());
            throw PaymentException.orderCreationFailed(e.getMessage());
        }
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_order_id", orderId);
            attributes.put("razorpay_payment_id", paymentId);
            attributes.put("razorpay_signature", signature);

            Utils.verifyPaymentSignature(attributes, keySecret);
            return true;
        } catch (RazorpayException e) {
            log.warn("Payment signature verification failed for order: {}", orderId);
            return false;
        }
    }

    @Override
    public void capturePayment(String paymentId, long amountPaise) {
        try {
            JSONObject captureRequest = new JSONObject();
            captureRequest.put("amount", amountPaise);
            captureRequest.put("currency", "INR");

            razorpayClient.payments.capture(paymentId, captureRequest);
            log.info("Captured payment: {}", paymentId);
        } catch (RazorpayException e) {
            log.error("Razorpay error capturing payment {}: {}", paymentId, e.getMessage());
            throw PaymentException.razorpayError("CAPTURE_FAILED", e.getMessage());
        }
    }

    @Override
    public RefundResult refundPayment(String paymentId, long amountPaise) {
        try {
            JSONObject refundRequest = new JSONObject();
            refundRequest.put("amount", amountPaise);

            Refund refund = razorpayClient.payments.refund(paymentId, refundRequest); // Fix: Refund instead of Payment

            return RefundResult.builder()
                    .success(true)
                    .refundId(refund.get("id"))
                    .amountRefunded(((Number) refund.get("amount")).longValue())
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay error refunding payment {}: {}", paymentId, e.getMessage());
            return RefundResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .build();
        }
    }

    @Override
    public String createCustomer(String email, String name, String phone) {
        try {
            JSONObject customerRequest = new JSONObject();
            customerRequest.put("name", name);
            customerRequest.put("email", email);
            customerRequest.put("contact", phone);

            Customer customer = razorpayClient.customers.create(customerRequest);
            return customer.get("id");
        } catch (RazorpayException e) {
            log.error("Razorpay error creating customer: {}", e.getMessage());
            throw PaymentException.razorpayError("CUSTOMER_CREATE_FAILED", e.getMessage());
        }
    }

    @Override
    public String createPaymentLink(long amountPaise, String currency, String description,
                                    String customerEmail, String customerName,
                                    String customerPhone, String receipt) {
        try {
            JSONObject paymentLinkRequest = new JSONObject();
            paymentLinkRequest.put("amount", amountPaise);
            paymentLinkRequest.put("currency", currency);
            paymentLinkRequest.put("description", description);
            paymentLinkRequest.put("reference_id", receipt);

            // Pre-fill customer details on the hosted Razorpay page
            JSONObject customer = new JSONObject();
            customer.put("name",  customerName);
            customer.put("email", customerEmail != null ? customerEmail : "");
            customer.put("contact", customerPhone != null ? customerPhone : "");
            paymentLinkRequest.put("customer", customer);

            // Notify tenant when customer pays (optional — configure webhook instead)
            JSONObject notify = new JSONObject();
            notify.put("sms", false);
            notify.put("email", true);
            paymentLinkRequest.put("notify", notify);

            // Reminders: Razorpay sends automated payment reminders to the customer
            paymentLinkRequest.put("reminder_enable", true);

            com.razorpay.PaymentLink paymentLink =
                    razorpayClient.paymentLink.create(paymentLinkRequest);

            String paymentLinkId = paymentLink.get("id");
            log.info("Razorpay PaymentLink created — id={}, receipt={}", paymentLinkId, receipt);
            return paymentLinkId;

        } catch (com.razorpay.RazorpayException e) {
            log.error("Razorpay createPaymentLink failed — receipt={}: {}", receipt, e.getMessage());
            throw new com.mtbs.shared.exception.PaymentException(
                    ErrorCode.RAZORPAY_ERROR,
                    "Payment link creation failed: " + e.getMessage());
        }
    }
}