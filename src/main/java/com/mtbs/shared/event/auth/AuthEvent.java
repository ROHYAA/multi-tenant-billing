package com.mtbs.shared.event.auth;

import com.mtbs.shared.event.DomainEvent;
import com.mtbs.shared.enums.notification.NotificationEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Event payload for authentication and user account domain events.
 *
 * Published by:  com.mtbs.auth.event.AuthEventPublisher
 * Consumed by:   com.mtbs.notification.service.NotificationService
 *                  → handleAuthEvent(AuthEvent)
 *
 * Covers:
 *   USER_REGISTERED      — new account created (signup or register)
 *   USER_LOGIN           — successful login (IP + device context)
 *   PASSWORD_CHANGED     — password updated by the user themselves
 *   PASSWORD_RESET_REQUESTED — forgot-password flow initiated
 *
 * MIGRATION NOTE:
 *   Replaces AuthNotificationEvent. AuthNotificationEvent should be
 *   deleted after NotificationService is updated to listen on AuthEvent.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthEvent implements DomainEvent {

    @Builder.Default
    private String eventId = UUID.randomUUID().toString();

    @Builder.Default
    private Instant occurredAt = Instant.now();

    // ── Event type ────────────────────────────────────────────────────────────

    /** Which auth event occurred. Must be one of the AUTH_* or USER_* values. */
    private NotificationEvent eventType;

    // ── Recipient ─────────────────────────────────────────────────────────────

    private String recipientEmail;
    private String recipientName;
    private String tenantName;

    // ── Login context (USER_LOGIN only) ───────────────────────────────────────

    /** Client IP address — set by AuthController.getClientIp() */
    private String ipAddress;

    /** User-Agent header value */
    private String deviceInfo;

    /** Timestamp of the login/event */
    private Instant eventTime;

    // ── Password reset context (PASSWORD_RESET_REQUESTED only) ───────────────

    /** Full reset URL including token — sent in the reset email */
    private String resetLink;

    /** Token expiry time — shown in the email body */
    private Instant resetTokenExpiresAt;

    // ── DomainEvent ───────────────────────────────────────────────────────────

    @Override
    public String getEventId() { return eventId; }

    @Override
    public Instant getOccurredAt() { return occurredAt; }

    @Override
    public String getEventType() {
        return eventType != null ? eventType.name() : "AUTH_EVENT";
    }
}