<?php

namespace App\Services;

use App\Models\AuditLog;
use App\Support\ApiResponse;
use App\Support\SpringPage;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;

class AdminAuditService
{
    public function search(Request $request): array
    {
        $query = AuditLog::query();
        if ($request->filled('whoUserId')) {
            $query->where('who_user_id', (int) $request->query('whoUserId'));
        }
        if ($request->filled('whatAction')) {
            $query->where('what_action', $request->query('whatAction'));
        }
        if ($request->filled('whereEntityType')) {
            $query->where('where_entity_type', $request->query('whereEntityType'));
        }
        if ($request->filled('whereEntityId')) {
            $query->where('where_entity_id', (int) $request->query('whereEntityId'));
        }
        if ($request->filled('tenantId')) {
            $query->where('context_tenant_id', (int) $request->query('tenantId'));
        }
        if ($request->filled('module')) {
            $query->where('module', $request->query('module'));
        }
        $this->applyDates($query, $request->query('startDate'), $request->query('endDate'));

        return $this->page($query, $request);
    }

    public function byUser(int $userId, Request $request): array
    {
        return $this->page(AuditLog::query()->where('who_user_id', $userId), $request);
    }

    public function byEntity(string $entityType, int $entityId, Request $request): array
    {
        return $this->page(
            AuditLog::query()->where('where_entity_type', $entityType)->where('where_entity_id', $entityId),
            $request
        );
    }

    public function byTenant(int $tenantId, Request $request): array
    {
        return $this->page(AuditLog::query()->where('context_tenant_id', $tenantId), $request);
    }

    public function byDateRange(Request $request): array
    {
        $query = AuditLog::query();
        $this->applyDates($query, $request->query('startDate'), $request->query('endDate'));

        return $this->page($query, $request);
    }

    public function statistics(Request $request): array
    {
        $since = $request->query('since')
            ? Carbon::parse($request->query('since'))->startOfDay()
            : now('UTC')->subDays(30);

        $rows = AuditLog::query()->where('created_at', '>=', $since)->get();
        $actionCounts = $rows->groupBy('what_action')->map->count()->all();

        return [
            'totalCount' => $rows->count(),
            'since' => $since->toISOString(),
            'actionCounts' => $actionCounts,
        ];
    }

    private function page($query, Request $request): array
    {
        [$rows, $page, $size, $total] = SpringPage::paginate($query, $request, 'created_at,desc');
        $content = $rows->map(fn (AuditLog $log) => [
            'id' => $log->id,
            'whoUserId' => $log->who_user_id,
            'whoUserEmail' => $log->who_user_email,
            'whoUserName' => $log->who_user_name,
            'whoRole' => $log->who_role,
            'whatAction' => $log->what_action,
            'whatDescription' => $log->description,
            'whereEntityType' => $log->where_entity_type,
            'whereEntityId' => $log->where_entity_id,
            'whereEntityName' => $log->where_entity_name,
            'changesBefore' => $log->changes_before,
            'changesAfter' => $log->changes_after,
            'changesSummary' => $log->changes_summary,
            'contextTenantId' => $log->context_tenant_id,
            'contextTenantName' => $log->context_tenant_name,
            'contextIpAddress' => $log->context_ip_address,
            'contextUserAgent' => $log->context_user_agent,
            'contextMetadata' => $log->context_metadata,
            'description' => $log->description,
            'module' => $log->module,
            'severity' => $log->severity,
            'createdAt' => optional($log->created_at)?->toISOString(),
        ])->values()->all();

        return ApiResponse::page($content, $page, $size, $total);
    }

    private function applyDates($query, mixed $startDate, mixed $endDate): void
    {
        if ($startDate) {
            $query->where('created_at', '>=', Carbon::parse($startDate)->startOfDay());
        }
        if ($endDate) {
            $query->where('created_at', '<', Carbon::parse($endDate)->addDay()->startOfDay());
        }
    }
}
