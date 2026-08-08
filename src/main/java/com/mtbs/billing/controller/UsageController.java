package com.mtbs.billing.controller;

import com.mtbs.billing.dto.UsageLimitsResponse;
import com.mtbs.billing.dto.UsageResponse;
import com.mtbs.billing.entity.Subscription;
import com.mtbs.billing.service.SubscriptionService;
import com.mtbs.billing.service.UsageService;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.enums.billing.SubscriptionStatus;
import com.mtbs.shared.enums.billing.UsageMetric;
import com.mtbs.shared.util.SecurityUtils;
import com.mtbs.tenant.entity.Plan;
import com.mtbs.tenant.repository.PlanRepository;
import com.mtbs.tenant.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/${api.version}/usage")
@RequiredArgsConstructor
@Tag(name = "Usage", description = "Usage tracking and plan limit enforcement")
@SecurityRequirement(name = "bearerAuth")
public class UsageController {

    private final PlanService planService;
    private final UsageService usageService;
    private final SubscriptionService subscriptionService;
    private final PlanRepository planRepository;

    @GetMapping("/limits")
    @Operation(
            summary = "Get real-time usage vs plan limits for current billing period",
            description = "Returns current usage counts and limits for API calls, active users, and storage. "
                    + "ACTIVE_USERS is a live count. Data is always fresh."
    )
    public ResponseEntity<ApiResponse<UsageLimitsResponse>> getUsageLimits() {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        UsageLimitsResponse response = usageService.getLimitsForCurrentPeriod(tenantId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(
            summary = "Get usage for a specific period",
            description = "Returns aggregated usage counts for API_CALLS and STORAGE_GB metrics. "
                    + "ACTIVE_USERS is included as a live count. "
                    + "Both start and end must be ISO-8601 instants."
    )
    public ResponseEntity<ApiResponse<List<UsageResponse>>> getUsageForPeriod(
            @Parameter(description = "Period start (ISO-8601, e.g. 2026-01-01T00:00:00Z)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,

            @Parameter(description = "Period end (ISO-8601, e.g. 2026-01-31T23:59:59Z)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {

        if (start.isAfter(end)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("start must be before end"));
        }

        Long tenantId = SecurityUtils.getCurrentTenantId();

        List<UsageResponse> responses = usageService.getUsageForPeriod(start, end);

        long activeUsersCount = usageService.getActiveUserCount(tenantId);
        Long usersLimit = null;
        
        Optional<Subscription> subOpt= subscriptionService.findFirstSubscriptionByStatuses(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING));
        if (subOpt.isPresent()) {
            Plan plan = planRepository.findById(subOpt.get().getPlanId()).orElse(null);
            if (plan != null) {
                usersLimit = planService.getMaxUsers(plan.getId());
            }
        }
        
        Long remaining = null;
        Double percentUsed = null;
        if (usersLimit != null && usersLimit > 0) {
            remaining = Math.max(usersLimit - activeUsersCount, 0L);
            percentUsed = Math.round((activeUsersCount * 100.0 / usersLimit) * 10.0) / 10.0;
        }
        
        responses.add(UsageResponse.builder()
                .metric(UsageMetric.ACTIVE_USERS)
                .current(activeUsersCount)
                .limit(usersLimit)
                .remaining(remaining)
                .percentUsed(percentUsed)
                .periodStart(start)
                .periodEnd(end)
                .build());

        return ResponseEntity.ok(ApiResponse.success(responses, "Usage for period fetched successfully"));
    }
}
