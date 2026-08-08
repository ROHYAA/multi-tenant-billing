package com.mtbs.app.filter;

import com.mtbs.shared.util.SecurityUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class
MdcSecurityEnrichmentFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            // Now SecurityContext is available
            Long tenantId = SecurityUtils.getCurrentTenantId();
            Long userId = SecurityUtils.getCurrentUserId();
            String role = SecurityUtils.getCurrentRole();

            MDC.put("tenantId", tenantId != null ? tenantId.toString() : "-");
            MDC.put("userId", userId != null ? userId.toString() : "-");
            MDC.put("role", role != null ? role : "-");

        } catch (Exception ignored) {}

        chain.doFilter(request, response);
    }
}