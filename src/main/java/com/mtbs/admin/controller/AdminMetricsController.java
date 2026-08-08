package com.mtbs.admin.controller;

import com.mtbs.admin.dto.AdminMetrics;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.admin.service.AdminMetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/${api.version}/admin/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Admin — Metrics", description = "Platform-wide aggregated metrics for the admin dashboard")
@SecurityRequirement(name = "bearerAuth")
public class AdminMetricsController {

    private final AdminMetricsService adminMetricsService;

    // ── GET /api/admin/metrics ────────────────────────────────────────────────

    @GetMapping
    @Operation(
        summary = "Get platform metrics",
        description = "Returns aggregated platform-wide metrics: " +
                      "tenant counts by status, subscription counts, invoice counts, " +
                      "total payment amounts, plan distribution, and failed payment counts. " +
                      "Response is cached for 15 minutes. " +
                      "Iterates all ACTIVE tenants and sets TenantContext per tenant for queries."
    )
    public ResponseEntity<ApiResponse<AdminMetrics>> getMetrics() {
        AdminMetrics metrics = adminMetricsService.getMetrics();
        return ResponseEntity.ok(ApiResponse.success(metrics, "Platform metrics fetched successfully"));
    }
}