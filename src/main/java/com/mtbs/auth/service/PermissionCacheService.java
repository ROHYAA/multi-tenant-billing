package com.mtbs.auth.service;

import com.mtbs.auth.entity.Permission;
import com.mtbs.auth.entity.RolePermission;
import com.mtbs.auth.repository.PermissionRepository;
import com.mtbs.auth.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionCacheService {

    private static final String KEY_PREFIX = "perms:";
    private static final String EMPTY_SENTINEL = "_empty_";
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate stringRedisTemplate;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;

    public Set<String> getPermissions(String schemaName, Long userId, Long roleId) {
        String key = buildCacheKey(schemaName, userId);

        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            log.debug("Permission cache HIT for schema={} userId={}", schemaName, userId);
            return parsePermissions(cached);
        }

        log.debug("Permission cache MISS for schema={} userId={} — loading from DB",
                schemaName, userId);

        List<RolePermission> rolePerms = rolePermissionRepository.findByRoleId(roleId);

        Set<Long> permissionIds = rolePerms.stream()
                .map(rp -> rp.getPermission().getId())
                .collect(Collectors.toSet());

        List<Permission> perms = permissionRepository.findAllById(permissionIds);

        Set<String> names = perms.stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());

        String value = String.join(",", names);
        stringRedisTemplate.opsForValue().set(
                key,
                value.isEmpty() ? EMPTY_SENTINEL : value,
                CACHE_TTL
        );

        return names;
    }

    public void evictUser(String schemaName, Long userId) {
        String key = buildCacheKey(schemaName, userId);
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("Permission cache evicted: schema={} userId={} existed={}",
                schemaName, userId, deleted);
    }

    public void evictTenant(String schemaName) {
        String pattern = KEY_PREFIX + schemaName + ":*";
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            Long count = stringRedisTemplate.delete(keys);
            log.info("Permission cache evicted for tenant schema={} keys={}",
                    schemaName, count);
        } else {
            log.debug("No cached permissions found for schema={}", schemaName);
        }
    }

    private String buildCacheKey(String schemaName, Long userId) {
        return KEY_PREFIX + schemaName + ":" + userId;
    }

    private Set<String> parsePermissions(String csv) {
        if (EMPTY_SENTINEL.equals(csv)) {
            return Collections.emptySet();
        }
        return new HashSet<>(Arrays.asList(csv.split(","))).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
