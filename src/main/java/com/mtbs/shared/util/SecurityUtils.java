package com.mtbs.shared.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.mtbs.auth.security.UserPrincipal;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static UserPrincipal getCurrentUserPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal) {
            return (UserPrincipal) authentication.getPrincipal();
        }
        return null;
    }

    public static Long getCurrentUserId() {
        UserPrincipal principal = getCurrentUserPrincipal();
        return principal != null ? principal.getId() : null;
    }

    public static String getCurrentUserEmail() {
        UserPrincipal principal = getCurrentUserPrincipal();
        return principal != null ? principal.getEmail() : null;
    }

    public static String getCurrentUserName() {
        UserPrincipal principal = getCurrentUserPrincipal();
        return principal != null ? principal.getUsername() : null;
    }

    public static Long getCurrentTenantId() {
        UserPrincipal principal = getCurrentUserPrincipal();
        return principal != null ? principal.getTenantId() : null;
    }

    public static String getCurrentSchemaName() {
        UserPrincipal principal = getCurrentUserPrincipal();
        return principal != null ? principal.getSchemaName() : null;
    }

    public static String getCurrentRole() {
        UserPrincipal principal = getCurrentUserPrincipal();
        return principal != null ? principal.getRole() : null;
    }

    public static String getClientIpAddress() {
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return null;
        }
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }

    public static String getUserAgent() {
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return null;
        }
        return request.getHeader("User-Agent");
    }

    private static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }
}
