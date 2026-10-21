<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\AdminTenantService;
use App\Support\ApiResponse;

class AdminUserController extends Controller
{
    public function __construct(private AdminTenantService $tenants) {}

    public function destroy(int $tenantId, int $userId)
    {
        $this->tenants->removeUser($tenantId, $userId);

        return ApiResponse::success(null, 'User removed from tenant successfully');
    }
}
