package com.mtbs.auth.event;

import com.mtbs.shared.event.auth.AuthNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes authentication-domain events.
 *
 * Used by: TenantAuthService, UserService, SignupService, PasswordResetService
 * Listener: NotificationService.handleAuthEvent(AuthNotificationEvent)
 *
 * Events routed through this publisher:
 *   USER_REGISTERED, USER_LOGIN, PASSWORD_CHANGED, PASSWORD_RESET_REQUESTED
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(AuthNotificationEvent event) {
        try {
            eventPublisher.publishEvent(event);
            log.debug("Auth event published: {}", event.getEventType());
        } catch (Exception e) {
            log.warn("Failed to publish auth event {}: {}", event.getEventType(), e.getMessage());
        }
    }
}