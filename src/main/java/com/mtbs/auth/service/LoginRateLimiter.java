package com.mtbs.auth.service;

import com.mtbs.shared.exception.AuthException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed rate limiter for login endpoints.
 *
 * Strategy: track failed attempts per IP using a counter key with a sliding
 * window TTL. On each failure, increment the counter and (re)set the TTL to
 * the window duration. After MAX_ATTEMPTS failures within the window the IP
 * is blocked for the remainder of the window.
 *
 * On successful login, the counter is cleared immediately so a legitimate
 * user who mis-typed once isn't penalised after they authenticate correctly.
 *
 * Redis key format: "login_attempts:{ip}"
 * Example:          "login_attempts:203.0.113.42"
 *
 * Config (application.yaml):
 *   app.auth.rate-limit.max-attempts: 5       # failures before lockout
 *   app.auth.rate-limit.window-minutes: 15    # sliding window in minutes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "login_attempts:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.auth.rate-limit.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.auth.rate-limit.window-minutes:15}")
    private long windowMinutes;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called BEFORE credential validation.
     * Throws AUTH_1007 (429) immediately if the IP is already locked out.
     */
    public void checkBlocked(String ip) {
        String key = buildKey(ip);
        String raw = stringRedisTemplate.opsForValue().get(key);

        if (raw != null && Integer.parseInt(raw) >= maxAttempts) {
            long ttl = getTtlSeconds(key);
            log.warn("Login blocked for IP={} — {} failed attempts, retry in {}s", ip, raw, ttl);
            throw AuthException.tooManyRequests(ttl > 0 ? ttl : windowMinutes * 60);
        }
    }

    /**
     * Called AFTER a failed credential check.
     * Increments the counter and (re)sets the window TTL.
     */
    public void recordFailure(String ip) {
        String key = buildKey(ip);

        // Increment — returns the new value after increment
        Long attempts = stringRedisTemplate.opsForValue().increment(key);

        // Set/reset the TTL on every failure so the window is sliding
        stringRedisTemplate.expire(key, windowMinutes, TimeUnit.MINUTES);

        log.warn("Failed login recorded for IP={} — attempt {}/{}", ip, attempts, maxAttempts);

        // If this increment pushed them over the limit, throw immediately
        // so this request also receives 429 (not just the next one)
        if (attempts != null && attempts >= maxAttempts) {
            long ttl = getTtlSeconds(key);
            throw AuthException.tooManyRequests(ttl > 0 ? ttl : windowMinutes * 60);
        }
    }

    /**
     * Called AFTER a successful login.
     * Clears the failure counter so legitimate users aren't penalised.
     */
    public void clearFailures(String ip) {
        stringRedisTemplate.delete(buildKey(ip));
        log.debug("Login rate limit cleared for IP={}", ip);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildKey(String ip) {
        return KEY_PREFIX + ip;
    }

    private long getTtlSeconds(String key) {
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0;
    }
}