package com.mtbs.notification.application;

import com.mtbs.notification.domain.NotificationRequest;
import com.mtbs.notification.template.TemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationOrchestrator {

    private final TemplateRenderer templateRenderer;
    private final ChannelRouter channelRouter;

    @Async
    public void process(NotificationRequest request) {
        log.debug("Processing notification: channel={}, template={}, tenant={}",
                request.getChannel(), request.getTemplateName(), request.getTenantId());
        try {
            String rendered = templateRenderer.render(
                    request.getTemplateName(), request.getVariables());
            channelRouter.route(request, rendered);
            log.debug("Notification sent successfully: template={}", request.getTemplateName());
        } catch (Exception e) {
            log.error("Notification failed: template={}, recipient={}, error={}",
                    request.getTemplateName(), request.getRecipient(), e.getMessage(), e);
        }
    }
}