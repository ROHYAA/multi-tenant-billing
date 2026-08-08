package com.mtbs.auth.service;

import com.mtbs.auth.entity.User;
import com.mtbs.auth.repository.UserRepository;
import com.mtbs.shared.exception.ResourceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenVersionCacheService {

    private static final String KEY_PREFIX = "tokver:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate stringRedisTemplate;
    private final UserRepository userRepository;

    public boolean isTokenVersionValid(String schemaName, Long userId, Long claimedVersion) {
        String key = buildKey(schemaName, userId);
        String cached = stringRedisTemplate.opsForValue().get(key);

        Long storedVersion;
        if (cached != null) {
            storedVersion = Long.parseLong(cached);
            log.debug("TokenVersion cache HIT: schema={} userId={} version={}",
                    schemaName, userId, storedVersion);
        } else {
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                log.warn("TokenVersion check: user not found userId={}", userId);
                return false;
            }
            User user = userOpt.get();
            storedVersion = user.getTokenVersion();
            stringRedisTemplate.opsForValue().set(
                    key,
                    String.valueOf(storedVersion),
                    CACHE_TTL);
            log.debug("TokenVersion loaded from DB: schema={} userId={} version={}",
                    schemaName, userId, storedVersion);
        }

        boolean valid = claimedVersion.equals(storedVersion);
        if (!valid) {
            log.warn("TokenVersion mismatch: schema={} userId={} claimed={} actual={}",
                    schemaName, userId, claimedVersion, storedVersion);
        }
        return valid;
    }

    @Transactional
    public void incrementTokenVersion(String schemaName, Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        User user = userOpt.orElseThrow(() -> ResourceException.notFound("User", userId));

        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        stringRedisTemplate.delete(buildKey(schemaName, userId));
        log.info("TokenVersion incremented: schema={} userId={} newVersion={}",
                schemaName, userId, user.getTokenVersion());
    }

    private String buildKey(String schemaName, Long userId) {
        return KEY_PREFIX + schemaName + ":" + userId;
    }
}