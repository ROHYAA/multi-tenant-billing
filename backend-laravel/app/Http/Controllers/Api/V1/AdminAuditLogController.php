<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\AdminAuditService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class AdminAuditLogController extends Controller
{
    public function __construct(private AdminAuditService $audits) {}

    public function index(Request $request)
    {
        return ApiResponse::success($this->audits->search($request), 'Audit logs fetched successfully');
    }

    public function byUser(int $userId, Request $request)
    {
        return ApiResponse::success($this->audits->byUser($userId, $request), 'Audit logs fetched successfully');
    }

    public function byEntity(string $entityType, int $entityId, Request $request)
    {
        return ApiResponse::success($this->audits->byEntity($entityType, $entityId, $request), 'Audit logs fetched successfully');
    }

    public function byTenant(int $tenantId, Request $request)
    {
        return ApiResponse::success($this->audits->byTenant($tenantId, $request), 'Audit logs fetched successfully');
    }

    public function byDateRange(Request $request)
    {
        $request->validate([
            'startDate' => ['required', 'date'],
            'endDate' => ['required', 'date'],
        ]);

        return ApiResponse::success($this->audits->byDateRange($request), 'Audit logs fetched successfully');
    }

    public function statistics(Request $request)
    {
        return ApiResponse::success($this->audits->statistics($request), 'Audit statistics fetched successfully');
    }
}
