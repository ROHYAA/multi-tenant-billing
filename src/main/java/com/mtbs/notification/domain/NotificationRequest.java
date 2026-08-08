package com.mtbs.notification.domain;

import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Builder
public class NotificationRequest {

    private final NotificationChannel channel;
    private final String recipient;
    private final String templateName;
    private final Map<String, Object> variables;
    private final NotificationPriority priority;
    private final String tenantId;
    private final byte[] pdfAttachment;
    private final String pdfFileName;

    @Builder.Default
    private final NotificationChannel channel$default = NotificationChannel.EMAIL;

    @Builder.Default
    private final NotificationPriority priority$default = NotificationPriority.NORMAL;

    @Builder.Default
    private final Map<String, Object> variables$default = new HashMap<>();
}