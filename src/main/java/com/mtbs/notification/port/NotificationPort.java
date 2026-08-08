package com.mtbs.notification.port;

import com.mtbs.notification.domain.NotificationChannel;
import com.mtbs.notification.domain.NotificationRequest;

public interface NotificationPort {
    NotificationChannel supportedChannel();
    void deliver(NotificationRequest request, String renderedContent);
}