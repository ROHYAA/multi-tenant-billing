package com.mtbs.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.mail")
@Getter
@Setter
public class NotificationProperties {
    private String fromAddress = "noreply@mtbs.com";
    private String fromName = "MTBS Platform";
}