package com.mtbs.notification.application;

import com.mtbs.notification.domain.NotificationRequest;
import com.mtbs.notification.port.NotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ChannelRouter {

    private final List<NotificationPort> ports;

    public void route(NotificationRequest request, String renderedContent) {
        ports.stream()
                .filter(port -> port.supportedChannel() == request.getChannel())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No provider registered for channel: " + request.getChannel()))
                .deliver(request, renderedContent);
    }
}