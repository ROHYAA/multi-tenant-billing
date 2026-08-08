package com.mtbs.admin.controller;

import com.mtbs.admin.dto.AuditLogResponse;
import com.mtbs.admin.service.AuditLogService;
import com.mtbs.shared.dto.common.ApiResponse;
import com.mtbs.shared.dto.common.PageResponse;
import com.mtbs.shared.enums.audit.AuditAction;
import com.mtbs.shared.enums.audit.AuditEntityType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

@RestController
@RequestMapping("/api/${api.version}/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Admin — Audit Logs", description = "Platform audit trail for all admin operations")
@SecurityRequirement(name = "bearerAuth")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Search audit logs with filters")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> searchAuditLogs(
            @Parameter(description = "Filter by admin user ID")
            @RequestParam(required = false) Long whoUserId,

            @Parameter(description = "Filter by action type")
            @RequestParam(required = false) AuditAction whatAction,

            @Parameter(description = "Filter by entity type")
            @RequestParam(required = false) AuditEntityType whereEntityType,

            @Parameter(description = "Filter by entity ID")
            @RequestParam(required = false) Long whereEntityId,

            @Parameter(description = "Filter by tenant ID")
            @RequestParam(required = false) Long tenantId,

            @Parameter(description = "Start date (ISO format)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,

            @Parameter(description = "End date (ISO format)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,

            @Parameter(description = "Filter by module (e.g., TENANT, USER, BILLING)")
            @RequestParam(required = false) String module,

            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Instant start = startDate != null ? startDate.atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        Instant end = endDate != null ? endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : null;

        Page<AuditLogResponse> logs = auditLogService.searchAuditLogs(
                whoUserId, whatAction, whereEntityType, whereEntityId,
                tenantId, start, end, module, pageable);

        return ResponseEntity.ok(ApiResponse.success(logs, "Audit logs fetched successfully"));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get audit logs by admin user")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> getAuditLogsByUser(
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<AuditLogResponse> logs = auditLogService.getAuditLogsByUser(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(logs), "Audit logs fetched successfully"));
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    @Operation(summary = "Get audit logs by entity")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> getAuditLogsByEntity(
            @PathVariable AuditEntityType entityType,
            @PathVariable Long entityId,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<AuditLogResponse> logs = auditLogService.getAuditLogsByEntity(entityType, entityId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(logs), "Audit logs fetched successfully"));
    }

    @GetMapping("/tenant/{tenantId}")
    @Operation(summary = "Get audit logs by tenant")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> getAuditLogsByTenant(
            @PathVariable Long tenantId,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<AuditLogResponse> logs = auditLogService.getAuditLogsByTenant(tenantId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(logs), "Audit logs fetched successfully"));
    }

    @GetMapping("/date-range")
    @Operation(summary = "Get audit logs by date range")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> getAuditLogsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable) {

        Instant start = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        Page<AuditLogResponse> logs = auditLogService.getAuditLogsByDateRange(start, end, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(logs), "Audit logs fetched successfully"));
    }

    @GetMapping("/statistics")
    @Operation(summary = "Get audit log statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAuditStatistics(
            @Parameter(description = "Since date (ISO format)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {

        Instant sinceInstant = since != null
                ? since.atStartOfDay().toInstant(ZoneOffset.UTC)
                : Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);

        Map<String, Object> stats = auditLogService.getAuditStatistics(sinceInstant);
        return ResponseEntity.ok(ApiResponse.success(stats, "Audit statistics fetched successfully"));
    }
}
