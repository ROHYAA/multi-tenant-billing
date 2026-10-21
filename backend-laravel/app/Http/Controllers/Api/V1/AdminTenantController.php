<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\ApproveTenantRequest;
use App\Http\Requests\ChangeTenantStatusRequest;
use App\Services\AdminTenantService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class AdminTenantController extends Controller
{
    public function __construct(private AdminTenantService $tenants) {}

    public function index(Request $request)
    {
        return ApiResponse::success($this->tenants->list($request), 'Tenants fetched successfully');
    }

    public function show(int $id)
    {
        return ApiResponse::success($this->tenants->detail($id), 'Tenant detail fetched successfully');
    }

    public function changeStatus(int $id, ChangeTenantStatusRequest $request)
    {
        $data = $request->validated();

        return ApiResponse::success(
            $this->tenants->changeStatus($id, $data['status'], $data['reason']),
            'Tenant status updated successfully'
        );
    }

    public function approve(int $id, ApproveTenantRequest $request)
    {
        $data = $request->validated();

        return ApiResponse::success(
            $this->tenants->approve($id, $data['planName'], $data['subscriptionExpiresAt']),
            'Tenant approved successfully'
        );
    }

    public function grantLifetime(int $id)
    {
        return ApiResponse::success(
            $this->tenants->grantLifetime($id),
            'Lifetime plan granted successfully'
        );
    }

    public function reactivate(int $id, ApproveTenantRequest $request)
    {
        $data = $request->validated();

        return ApiResponse::success(
            $this->tenants->reactivate($id, $data['planName'], $data['subscriptionExpiresAt']),
            'Tenant reactivated successfully'
        );
    }

    public function users(int $id, Request $request)
    {
        return ApiResponse::success($this->tenants->users($id, $request), 'Tenant users fetched successfully');
    }

    public function destroy(int $id)
    {
        $this->tenants->delete($id);

        return ApiResponse::success(null, 'Tenant deleted successfully');
    }
}
