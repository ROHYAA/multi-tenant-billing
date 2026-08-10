package com.mtbs.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage")
@Getter
@Setter
public class StorageProperties {
    private String localPath = "./uploads";
    private long maxFileSizeBytes = 2 * 1024 * 1024; // 2MB
}
