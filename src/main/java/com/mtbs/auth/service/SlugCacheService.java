package com.mtbs.auth.service;

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
public class SlugCacheService {

    private static final String KEY_PREFIX = "tslug:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final StringRedisTemplate stringRedisTemplate;
    private final TenantService tenantService;

    public Long resolveTenantId(String slug) {
        String normalizedSlug = slug.toLowerCase().trim();
        String key = buildKey(normalizedSlug);

        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("Slug cache HIT: slug={} tenantId={}", normalizedSlug, cached);
            return Long.parseLong(cached);
        }

        Tenant tenant = tenantService.findTenantBySlug(normalizedSlug);

        stringRedisTemplate.opsForValue().set(
                key,
                String.valueOf(tenant.getId()),
                CACHE_TTL);
        log.info("Slug resolved and cached: slug={} tenantId={}", normalizedSlug, tenant.getId());
        return tenant.getId();
    }

    public void evict(String slug) {
        String normalizedSlug = slug.toLowerCase().trim();
        String key = buildKey(normalizedSlug);
        Boolean deleted = stringRedisTemplate.delete(key);
        log.info("Slug cache evicted: slug={} existed={}", normalizedSlug, deleted);
    }

    private String buildKey(String slug) {
        return KEY_PREFIX + slug.toLowerCase();
    }
}