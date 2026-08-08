package com.mtbs.billing.controller.webhook;

import com.mtbs.shared.exception.PaymentException;
import com.mtbs.billing.service.PaymentService;
import com.razorpay.Utils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles inbound Razorpay webhook events.
 *
 * Security model:
 *  - This endpoint is PUBLIC (no JWT) — Razorpay calls it from their servers.
 *  - Authentication is via HMAC-SHA256 signature verification using the
 *    webhook secret. Every request is verified before any business logic runs.
 *  - Returning 200 OK tells Razorpay the event was received. Any non-2xx
 *    causes Razorpay to retry with exponential backoff.
 *
 * Events handled:
 *  - payment.captured  → handlePaymentSuccess
 *  - payment.failed    → handlePaymentFailure
 *  - All other events  → acknowledged but ignored (returns 200)
 */
@RestController
@RequestMapping("/api/${api.version}/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Razorpay inbound webhook receiver")
public class RazorpayWebhookController {

    private final PaymentService paymentService;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    // ── POST /api/webhooks/razorpay ───────────────────────────────────────────

    @PostMapping("/razorpay")
    @Operation(
            summary = "Razorpay webhook receiver",
            description = "Receives and processes Razorpay webhook events. " +
                    "Authenticated via HMAC-SHA256 signature in the X-Razorpay-Signature header. " +
                    "Always returns 200 OK on signature validation success, regardless of event type."
    )
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String razorpaySignature) {

        // 1. Verify signature before touching any business logic
        verifySignature(payload, razorpaySignature);

        JSONObject event = new JSONObject(payload);
        String eventType = event.optString("event");

        log.info("Razorpay webhook received: event={}", eventType);

        try {
            switch (eventType) {
                case "payment.captured" -> {
                    JSONObject paymentEntity = event
                            .getJSONObject("payload")
                            .getJSONObject("payment")
                            .getJSONObject("entity");
                    String paymentId = paymentEntity.getString("id");
                    paymentService.handlePaymentSuccess(paymentId);
                    log.info("Payment captured handled: razorpayPaymentId={}", paymentId);
                }
                case "payment.failed" -> {
                    JSONObject paymentEntity = event
                            .getJSONObject("payload")
                            .getJSONObject("payment")
                            .getJSONObject("entity");
                    String paymentId   = paymentEntity.getString("id");
                    String failureCode = paymentEntity.optString("error_code", "UNKNOWN");
                    String failureMsg  = paymentEntity.optString("error_description", "Payment failed");
                    paymentService.handlePaymentFailure(paymentId, failureCode, failureMsg);
                    log.info("Payment failure handled: razorpayPaymentId={}, code={}", paymentId, failureCode);
                }
                default -> log.debug("Unhandled Razorpay event type: {}", eventType);
            }
        } catch (Exception e) {
            // Log but still return 200 — prevents Razorpay from retrying for
            // events we successfully received but failed to process internally.
            // Internal alerting/monitoring should catch these.
            log.error("Error processing Razorpay event={}: {}", eventType, e.getMessage(), e);
        }

        return ResponseEntity.ok("ok");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void verifySignature(String payload, String signature) {
        try {
            boolean valid = Utils.verifyWebhookSignature(payload, signature, webhookSecret);
            if (!valid) {
                log.warn("Razorpay webhook signature verification FAILED");
                throw PaymentException.invalidSignature();
            }
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Razorpay signature verification error: {}", e.getMessage());
            throw PaymentException.invalidSignature();
        }
    }
}