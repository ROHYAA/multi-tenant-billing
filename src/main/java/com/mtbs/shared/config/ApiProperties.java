package com.mtbs.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "api")
@Getter
@Setter
public class ApiProperties {
    private String version = "v1";
    
    public String getAuthRefreshPath() {
        return "/api/" + version + "/auth/refresh";
    }
    
    public String getAdminAuthRefreshPath() {
        return "/api/" + version + "/admin/auth/refresh";
    }
}