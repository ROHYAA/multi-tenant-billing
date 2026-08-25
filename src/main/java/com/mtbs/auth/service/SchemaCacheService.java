package com.mtbs.auth.service;

import com.mtbs.shared.enums.auth.Status;
import com.mtbs.shared.exception.TenantException;
import com.mtbs.tenant.entity.Shop;
import com.mtbs.tenant.service.ShopService;
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
    private static final String STATUS_KEY_PREFIX = "tenant-status:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final StringRedisTemplate stringRedisTemplate;
    private final ShopService tenantService;

    public String resolveSchemaName(Long tenantId) {
        String key = buildKey(tenantId);
        String cached = stringRedisTemplate.opsForValue().get(key);

        if (cached != null) {
            log.debug("Schema cache HIT: tenantId={} schema={}", tenantId, cached);
            return cached;
        }

        Shop tenant = tenantService.getTenantById(tenantId);

        String schemaName = tenant.getSchemaName();
        stringRedisTemplate.opsForValue().set(key, schemaName, CACHE_TTL);
        log.info("Schema resolved and cached: tenantId={} schema={}", tenantId, schemaName);
        return schemaName;
    }

    /** Used by JwtAuthenticationFilter to block writes for non-ACTIVE (e.g. PENDING_APPROVAL) tenants. */
    public Status resolveStatus(Long tenantId) {
        String key = STATUS_KEY_PREFIX + tenantId;
        String cached = stringRedisTemplate.opsForValue().get(key);

        if (cached != null) {
            return Status.valueOf(cached);
        }

        Shop tenant = tenantService.getTenantById(tenantId);
        Status status = tenant.getStatus();
        stringRedisTemplate.opsForValue().set(key, status.name(), CACHE_TTL);
        return status;
    }

    public void evict(Long tenantId) {
        String key = buildKey(tenantId);
        Boolean deleted = stringRedisTemplate.delete(key);
        stringRedisTemplate.delete(STATUS_KEY_PREFIX + tenantId);
        log.info("Schema cache evicted: tenantId={} existed={}", tenantId, deleted);
    }

    private String buildKey(Long tenantId) {
        return KEY_PREFIX + tenantId;
    }
}