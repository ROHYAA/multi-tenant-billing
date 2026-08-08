package com.mtbs.auth.service;

import com.mtbs.shared.exception.TenantException;
import com.mtbs.tenant.entity.Tenant;
import com.mtbs.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaCacheService {

    private static final String KEY_PREFIX = "schema:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final StringRedisTemplate stringRedisTemplate;
    private final TenantService tenantService;

    public String resolveSchemaName(Long tenantId) {
        String key = buildKey(tenantId);
        String cached = stringRedisTemplate.opsForValue().get(key);

        if (cached != null) {
            log.debug("Schema cache HIT: tenantId={} schema={}", tenantId, cached);
            return cached;
        }

        Tenant tenant = tenantService.getTenantById(tenantId);

        String schemaName = tenant.getSchemaName();
        stringRedisTemplate.opsForValue().set(key, schemaName, CACHE_TTL);
        log.info("Schema resolved and cached: tenantId={} schema={}", tenantId, schemaName);
        return schemaName;
    }

    public void evict(Long tenantId) {
        String key = buildKey(tenantId);
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("Schema cache evicted: tenantId={} existed={}", tenantId, deleted);
    }

    private String buildKey(Long tenantId) {
        return KEY_PREFIX + tenantId;
    }
}