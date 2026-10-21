<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\ShopSettingsService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class ShopSettingsController extends Controller
{
    public function __construct(private ShopSettingsService $settings) {}

    public function show()
    {
        return ApiResponse::success($this->settings->get(), 'Shop settings fetched successfully');
    }

    public function update(Request $request)
    {
        return ApiResponse::success(
            $this->settings->update($request->except(['id', 'shop_id', 'shopId', 'tenantId', 'source_id'])),
            'Shop settings updated successfully'
        );
    }
}
