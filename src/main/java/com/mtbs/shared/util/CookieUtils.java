package com.mtbs.shared.util;

import com.mtbs.shared.config.ApiProperties;
import com.mtbs.shared.constant.CookieConstants;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Production-grade, enterprise-level cookie utility for token management.
 * 
 * Responsibilities:
 *  - Centralized cookie configuration (security flags, paths, lifetimes)
 *  - HttpOnly, Secure, SameSite enforcement on all cookies
 *  - Token extraction and validation from request cookies
 *  - Cookie creation and clearing lifecycle management
 * 
 * Configuration is injected via @Value and ApiProperties:
 *  - app.cookie.secure: false (dev) | true (prod)
 *  - jwt.expiration: Access token lifetime (milliseconds)
 *  - jwt.refresh-expiration: Refresh token lifetime (milliseconds)
 *  - api.version: Used to construct refresh paths
 * 
 * IMPORTANT: Tokens are NEVER stored in localStorage by frontend.
 * HttpOnly cookies are managed entirely server-side; JS cannot access them.
 * Browsers automatically send cookies on each request to matching paths.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CookieUtils {

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${jwt.expiration:900000}")
    private long jwtExpirationMs;

    @Value("${jwt.refresh-expiration:604800000}")
    private long jwtRefreshExpirationMs;


    // ── PUBLIC API ────────────────────────────────────────────────────────────

    /**
     * Creates an HttpOnly cookie for token storage with proper security headers.
     * ResponseCookie handles SameSite natively (Spring 5+).
     * 
     * @param name Cookie name (from CookieConstants)
     * @param value JWT token value
     * @param path Cookie path (scopes when browser sends it)
     * @param maxAgeSeconds Time-to-live in seconds (converted from milliseconds)
     * @return ResponseCookie with HttpOnly, Secure, SameSite set
     */
    public ResponseCookie createTokenCookie(
            String name,
            String value,
            String path,
            long maxAgeSeconds) {

        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(CookieConstants.SAME_SITE)
                .path(path)
                .maxAge(maxAgeSeconds)
                .build();
    }

    /**
     * Creates a cookie that clears the named cookie on the client by setting maxAge=0.
     * Used during logout to remove tokens from browser storage.
     * 
     * @param name Cookie name to clear
     * @param path Cookie path (must match original path)
     * @return ResponseCookie with maxAge=0 for immediate deletion
     */
    public ResponseCookie clearCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(CookieConstants.SAME_SITE)
                .path(path)
                .maxAge(0)
                .build();
    }

    /**
     * Reads a named cookie value from HttpServletRequest.
     * Safe extraction with null/blank checking.
     * 
     * @param request HttpServletRequest from controller
     * @param name Cookie name to extract
     * @return Optional.of(value) if found and not blank; Optional.empty() otherwise
     */
    public Optional<String> getCookieValue(
            HttpServletRequest request,
            String name) {

        if (request.getCookies() == null) {
            return Optional.empty();
        }

        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Extracts access token from HttpOnly cookie.
     * Primary method for JWT extraction in security filter.
     */
    public Optional<String> extractAccessToken(HttpServletRequest request) {
        return getCookieValue(request, CookieConstants.ACCESS_TOKEN_COOKIE);
    }

    /**
     * Extracts refresh token from HttpOnly cookie.
     * Used during token refresh and logout flows.
     */
    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        return getCookieValue(request, CookieConstants.REFRESH_TOKEN_COOKIE);
    }

    /**
     * Sets both access and refresh token cookies for tenant users.
     * Automatically calculates max-age from injected JWT expiration values.
     * 
     * Access token path: "/" (available to all endpoints)
     * Refresh token path: "/api/{version}/auth/refresh" (narrowed scope)
     * 
     * @param response HttpServletResponse to add Set-Cookie headers
     * @param accessToken JWT access token from JwtTokenProvider
     * @param refreshToken JWT refresh token from JwtTokenProvider
     */
    public void addAuthCookies(
            HttpServletResponse response,
            String accessToken,
            String refreshToken) {

        long accessTokenMaxAge = jwtExpirationMs / 1000;
        long refreshTokenMaxAge = jwtRefreshExpirationMs / 1000;

        // Add access token cookie (broad path, relatively short-lived)
        ResponseCookie accessCookie = createTokenCookie(
                CookieConstants.ACCESS_TOKEN_COOKIE,
                accessToken,
                CookieConstants.ACCESS_PATH,
                accessTokenMaxAge);
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

        // Add refresh token cookie (narrow path, long-lived)
        // Path scoped to /api/{version}/auth/refresh prevents accidental replay
        ResponseCookie refreshCookie = createTokenCookie(
                CookieConstants.REFRESH_TOKEN_COOKIE,
                refreshToken,
                CookieConstants.REFRESH_PATH,
                refreshTokenMaxAge);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        log.debug("Auth cookies added to response (TTL: access={}s, refresh={}s)", 
                accessTokenMaxAge, refreshTokenMaxAge);
    }

    /**
     * Sets both access and refresh token cookies for admin/SUPER_ADMIN users.
     * Similar to addAuthCookies but refresh token uses admin-specific path.
     * 
     * Admin refresh token path: "/api/{version}/admin/auth/refresh"
     * 
     * @param response HttpServletResponse to add Set-Cookie headers
     * @param accessToken JWT access token for admin user
     * @param refreshToken JWT refresh token for admin user (different path)
     */
    public void addAdminAuthCookies(
            HttpServletResponse response,
            String accessToken,
            String refreshToken) {

        long accessTokenMaxAge = jwtExpirationMs / 1000;
        long refreshTokenMaxAge = jwtRefreshExpirationMs / 1000;

        // Add access token cookie
        ResponseCookie accessCookie = createTokenCookie(
                CookieConstants.ACCESS_TOKEN_COOKIE,
                accessToken,
                CookieConstants.ACCESS_PATH,
                accessTokenMaxAge);
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());

        // Add admin refresh token cookie (admin-specific path)
        ResponseCookie refreshCookie = createTokenCookie(
                CookieConstants.REFRESH_TOKEN_COOKIE,
                refreshToken,
                CookieConstants.ADMIN_REFRESH_PATH,
                refreshTokenMaxAge);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        log.debug("Admin auth cookies added to response (TTL: access={}s, refresh={}s)", 
                accessTokenMaxAge, refreshTokenMaxAge);
    }

    /**
     * Clears all auth cookies by setting their maxAge to 0.
     * Called during logout to remove tokens from browser.
     * 
     * Clears:
     *  - access_token (path: /)
     *  - refresh_token (path: /api/{version}/auth/refresh)
     *  - refresh_token (path: /api/{version}/admin/auth/refresh)
     * 
     * @param response HttpServletResponse to add Set-Cookie headers with maxAge=0
     */
    public void clearAuthCookies(HttpServletResponse response) {
        // Clear access token cookie (path: /)
        ResponseCookie clearAccessCookie = clearCookie(
                CookieConstants.ACCESS_TOKEN_COOKIE,
                CookieConstants.ACCESS_PATH);
        response.addHeader(HttpHeaders.SET_COOKIE, clearAccessCookie.toString());

        // Clear refresh token cookie (path: /api/{version}/auth/refresh)
        ResponseCookie clearRefreshCookie = clearCookie(
                CookieConstants.REFRESH_TOKEN_COOKIE,
                CookieConstants.REFRESH_PATH);
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie.toString());

        // Clear admin refresh token cookie (path: /api/{version}/admin/auth/refresh)
        // In case user was admin before logout
        clearAdminAuthCookies(response);
    }

    /**
     * Clears admin auth cookies (access + admin refresh paths).
     * Used for SUPER_ADMIN logout.
     */
    public void clearAdminAuthCookies(HttpServletResponse response) {
        ResponseCookie clearAdminAuthCookie = clearCookie(
                CookieConstants.ACCESS_TOKEN_COOKIE,
                CookieConstants.ACCESS_PATH);
        response.addHeader(HttpHeaders.SET_COOKIE, clearAdminAuthCookie.toString());

        ResponseCookie clearAdminRefreshCookie = clearCookie(
                CookieConstants.REFRESH_TOKEN_COOKIE,
                CookieConstants.ADMIN_REFRESH_PATH);
        response.addHeader(HttpHeaders.SET_COOKIE, clearAdminRefreshCookie.toString());

        log.debug("All auth cookies cleared from response");
    }

    // ── DIAGNOSTICS & LOGGING ────────────────────────────────────────────────

    /**
     * Log current cookie configuration (salt/security summary).
     * Called on startup for audit trail.
     */
    public void logConfiguration() {
        log.info("CookieUtils initialized: secure={}, jwtExpiry={}ms, refreshExpiry={}ms, refreshPath={}",
                cookieSecure, jwtExpirationMs, jwtRefreshExpirationMs, CookieConstants.REFRESH_PATH);
    }
}
