package com.mtbs.shared.event.auth;

import com.mtbs.shared.enums.notification.NotificationEvent;
import com.mtbs.shared.event.DomainEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthNotificationEvent implements DomainEvent {

    private NotificationEvent eventType;

    @Override
    public String getEventType() {
        return eventType != null ? eventType.name() : null;
    }

    private String recipientEmail;
    private String recipientName;
    private String tenantName;
    private String ipAddress;
    private String deviceInfo;
    private Instant eventTime;

    private String resetLink;
}