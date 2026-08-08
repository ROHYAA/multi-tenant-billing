package legacy.saasbilling.billing.aspect;

import legacy.saasbilling.shared.annotation.FeatureGate;
import legacy.saasbilling.shared.enums.UsageMetric;
import legacy.saasbilling.tenant.service.PlanLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class FeatureGateAspect {

    private final PlanLimitService planLimitService;

    @Around("@annotation(featureGate)")
    public Object enforceFeatureGate(ProceedingJoinPoint joinPoint, FeatureGate featureGate) throws Throwable {
        UsageMetric metric = featureGate.metric();
        log.debug("Checking feature gate for metric: {}", metric);

        planLimitService.enforceLimitForMetric(metric);

        return joinPoint.proceed();
    }
}
