package com.mtbs.auth.security;

import com.mtbs.auth.service.PermissionCacheService;
import com.mtbs.auth.service.SchemaCacheService;
import com.mtbs.auth.service.TokenVersionCacheService;
import com.mtbs.shared.constant.SecurityConstants;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.shared.util.CookieUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private void sendUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"success\":false,\"error\":{\"code\":\"%s\",\"message\":\"%s\"}}",
                code, message));
    }
}