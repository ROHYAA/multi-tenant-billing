package com.mtbs.billing.controller;

import com.mtbs.admin.dto.AdminMetrics;
import com.mtbs.billing.dto.TenantBillingDashboard;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.admin.service.AdminMetricsService;
import com.mtbs.billing.service.TenantBillingDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/${api.version}/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Billing dashboard endpoints")
@Validated
public class DashboardController {

    private final TenantBillingDashboardService dashboardService;
    private final AdminMetricsService adminMetricsService;

    @GetMapping("/billing")
    @Operation(summary = "Get tenant billing dashboard", description = "Returns billing overview for the current tenant.")
    public ResponseEntity<ApiResponse<TenantBillingDashboard>> getBillingDashboard(
            @Parameter(description = "Number of days for usage trends (default 7, max 30)")
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int days) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getDashboard(days)));
    }

    @GetMapping("/admin/metrics")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    @Operation(summary = "Get platform admin metrics", description = "Returns platform-wide admin metrics.")
    public ResponseEntity<ApiResponse<AdminMetrics>> getAdminMetrics() {
        return ResponseEntity.ok(ApiResponse.success(adminMetricsService.getMetrics()));
    }
}
