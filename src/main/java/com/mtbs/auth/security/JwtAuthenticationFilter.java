package com.mtbs.auth.security;

import com.mtbs.auth.service.PermissionCacheService;
import com.mtbs.auth.service.SchemaCacheService;
import com.mtbs.auth.service.TokenVersionCacheService;
import com.mtbs.shared.constant.SecurityConstants;
import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.shared.util.CookieUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import io.jsonwebtoken.Claims;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CookieUtils cookieUtils;
    private final SchemaCacheService schemaCacheService;
    private final TokenVersionCacheService tokenVersionCacheService;
    private final PermissionCacheService permissionCacheService;

    @Value("${api.version}")
    private String apiVersion;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        log.debug("JWT filter executing for: {}", request.getRequestURI());
        String jwt = getJwtFromRequest(request);

        if (!StringUtils.hasText(jwt) || !jwtTokenProvider.validateToken(jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        Claims claims = jwtTokenProvider.getClaimFromToken(jwt, Function.identity());
        Boolean isSuperAdmin = claims.get("isSuperAdmin", Boolean.class);

        if (Boolean.TRUE.equals(isSuperAdmin)) {
            UserPrincipal principal = new UserPrincipal(
                    Long.parseLong(claims.getSubject()),
                    claims.get("email", String.class),
                    null, null, null, null, null,
                    List.of(SecurityConstants.SUPER_ADMIN_ROLE));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);
            return;
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(jwt);
        Long tenantId;
        Long roleId;
        Long tokenVersion;
        String schemaName;

        try {
            tenantId = jwtTokenProvider.getTenantIdFromToken(jwt);
            roleId = jwtTokenProvider.getRoleIdFromToken(jwt);
            tokenVersion = jwtTokenProvider.getTokenVersionFromToken(jwt);

            schemaName = schemaCacheService.resolveSchemaName(tenantId);

            TenantContext.setTenantId(tenantId);
            TenantContext.setCurrentSchema(schemaName);
            log.debug("TenantContext set: tenantId={} schema={}", tenantId, schemaName);

            if (isWriteBlockedForTenantStatus(request, tenantId)) {
                log.warn("Blocked write for non-ACTIVE tenant: tenantId={} method={} path={}",
                        tenantId, request.getMethod(), request.getRequestURI());
                sendForbidden(response, "TENANT_PENDING_APPROVAL",
                        "Your shop is pending approval — you can view data but cannot make changes yet");
                return;
            }

            if (!tokenVersionCacheService.isTokenVersionValid(schemaName, userId, tokenVersion)) {
                log.warn("Rejected revoked token: userId={} claimedVersion={}", userId, tokenVersion);
                sendUnauthorized(response, "TOKEN_REVOKED", "Token has been revoked");
                return;
            }

            Set<String> permissions = permissionCacheService.getPermissions(schemaName, userId, roleId);

            List<GrantedAuthority> authorities = permissions.stream()
                    .map(p -> new SimpleGrantedAuthority("PERMISSION_" + p))
                    .collect(Collectors.toList());

            UserPrincipal principal = new UserPrincipal(
                    userId, null, null, tenantId, schemaName, roleId, null, new java.util.ArrayList<>(permissions));

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, authorities);
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.error("Auth filter failure userId={}: {}", userId, e.getMessage(), e);
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            sendUnauthorized(response, "UNAUTHORIZED", "Authorization service unavailable");
        }
    }

    /**
     * True if this request is a write (not GET/HEAD/OPTIONS) against a tenant
     * whose shop status isn't ACTIVE (currently only PENDING_APPROVAL uses this —
     * SUSPENDED/INACTIVE tenants are already blocked entirely at login/refresh).
     * Auth-lifecycle endpoints (refresh/logout) are exempt — those must always
     * work regardless of approval status.
     */
    private boolean isWriteBlockedForTenantStatus(HttpServletRequest request, Long tenantId) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return false;
        }
        if (request.getRequestURI().startsWith("/api/" + apiVersion + "/auth/")) {
            return false;
        }
        return schemaCacheService.resolveStatus(tenantId) != Status.ACTIVE;
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        Optional<String> cookieToken = cookieUtils.extractAccessToken(request);
        if (cookieToken.isPresent()) {
            return cookieToken.get();
        }
        
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(SecurityConstants.BEARER_PREFIX)) {
            return bearerToken.substring(7);
        }
        return null;
    }

    // Both write the standard ApiResponse envelope shape (top-level "message"/
    // "errorCode") — the frontend's api-response-interceptor only reads a
    // top-level "message" field to surface error text to the user; a nested
    // shape here would silently fall back to a generic error message instead.
    private void sendUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }

    private void sendForbidden(HttpServletResponse response, String code, String message) throws IOException {
        writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, code, message);
    }

    private void writeErrorResponse(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"success\":false,\"message\":\"%s\",\"errorCode\":\"%s\"}",
                message, code));
    }
}