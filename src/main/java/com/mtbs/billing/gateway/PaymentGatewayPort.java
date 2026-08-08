package com.mtbs.billing.gateway;

import com.mtbs.billing.dto.OrderResponse;
import com.mtbs.billing.dto.RefundResult;

public interface PaymentGatewayPort {

    /**
     * Creates a Razorpay Order â€” equivalent to Stripe PaymentIntent.
     * 
     * @param amountPaise amount in smallest currency unit (paise for INR)
     * @param currency    currency code (e.g., "INR")
     * @param receipt     idempotency key / receipt reference
     * @return OrderResponse with order_id and checkout details
     */
    OrderResponse createOrder(long amountPaise, String currency, String receipt);

    /**
     * Verifies payment signature after frontend checkout completes.
     * Computes HMAC_SHA256(orderId + "|" + paymentId, keySecret) and compares with
     * signature.
     * 
     * @return true if signature is valid
     */
    boolean verifyPaymentSignature(String orderId, String paymentId, String signature);

    /**
     * Captures payment manually if not auto-captured.
     * 
     * @param paymentId   Razorpay payment ID (pay_XXX)
     * @param amountPaise amount to capture in paise
     */
    void capturePayment(String paymentId, long amountPaise);

    /**
     * Refund a captured payment.
     * 
     * @param paymentId   Razorpay payment ID (pay_XXX)
     * @param amountPaise amount to refund in paise
     * @return RefundResult with refund details
     */
    RefundResult refundPayment(String paymentId, long amountPaise);

    /**
     * Create a Razorpay customer.
     * 
     * @return Razorpay customer ID
     */
    String createCustomer(String email, String name, String phone);

    /**
     * Creates a Razorpay Payment Link for a business invoice.
     *
     * The payment link is a hosted Razorpay page where the customer can pay
     * using any supported method (UPI, card, netbanking, etc.) without any
     * integration on your side.
     *
     * @param amountPaise    Amount in paise (INR smallest unit). e.g. ₹1,000 = 100000 paise.
     * @param currency       Currency code — always "INR" for Razorpay Payment Links.
     * @param description    Short description shown on the payment page. e.g. "Invoice BINV-1-202603-0001"
     * @param customerEmail  Customer's email — pre-fills the Razorpay checkout form.
     * @param customerName   Customer's name  — pre-fills the Razorpay checkout form.
     * @param customerPhone  Customer's phone — pre-fills the Razorpay checkout form.
     * @param receipt        Idempotency key / your internal reference (invoice number).
     * @return               Razorpay Payment Link ID (plink_XXXX). Store on BusinessInvoice.
     */
    String createPaymentLink(
            long amountPaise,
            String currency,
            String description,
            String customerEmail,
            String customerName,
            String customerPhone,
            String receipt
    );
}
