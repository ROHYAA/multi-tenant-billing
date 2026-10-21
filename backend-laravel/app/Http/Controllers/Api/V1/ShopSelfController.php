<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\UpdateShopRequest;
use App\Services\ShopSelfService;
use App\Support\ApiResponse;

class ShopSelfController extends Controller
{
    public function __construct(private ShopSelfService $shops) {}

    public function show()
    {
        return ApiResponse::success($this->shops->current(), 'Tenant details retrieved');
    }

    public function update(UpdateShopRequest $request)
    {
        return ApiResponse::success($this->shops->update($request->validated()), 'Tenant updated successfully');
    }

    public function schema()
    {
        return ApiResponse::success($this->shops->schemaInfo(), 'Schema info retrieved');
    }

    public function status()
    {
        return ApiResponse::success($this->shops->status(), 'Tenant status retrieved');
    }

    public function deactivate()
    {
        $this->shops->deactivate();

        return ApiResponse::success(null, 'Tenant deactivated successfully');
    }

    public function reactivate()
    {
        $this->shops->reactivate();

        return ApiResponse::success(null, 'Tenant reactivated successfully');
    }
}
