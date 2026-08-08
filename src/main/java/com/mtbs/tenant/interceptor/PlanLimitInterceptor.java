package com.mtbs.tenant.interceptor;

import com.mtbs.shared.annotation.TrackUsage;
import com.mtbs.shared.enums.billing.UsageMetric;
import com.mtbs.shared.exception.ResourceException;
import com.mtbs.shared.multitenancy.TenantContext;
import com.mtbs.tenant.service.PlanLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.YearMonth;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Component
@RequiredArgsConstructor
@Slf4j
public class PlanLimitInterceptor implements HandlerInterceptor {

    private final PlanLimitService planLimitService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        TrackUsage trackUsage =
                handlerMethod.getMethodAnnotation(TrackUsage.class);

        if (trackUsage == null) {
            return true;
        }

        if (trackUsage.metric() != UsageMetric.API_CALLS) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("SUPER_ADMIN"))) {
            return true;
        }

        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return true;
        }

        String yearMonth = YearMonth.now().toString();
        String key = "api:" + tenantId + ":" + yearMonth;

        try {
            String val = redisTemplate.opsForValue().get(key);
            Long currentUsage = val != null ? Long.parseLong(val) : 0L;

            planLimitService.checkApiCallLimit(currentUsage);

            Long newVal = redisTemplate.opsForValue().increment(key);
            if (newVal != null && newVal == 1L) {
                redisTemplate.expire(key, 32, TimeUnit.DAYS);
            }
        } catch (ResourceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis unavailable for API limit check, allowing request: {}", e.getMessage());
        }

        return true;
    }
}
