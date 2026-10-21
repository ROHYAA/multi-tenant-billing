<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\BusinessTypeCatalog;
use App\Support\ApiResponse;

class BusinessTypeController extends Controller
{
    public function __invoke(BusinessTypeCatalog $catalog)
    {
        return ApiResponse::success($catalog->listActive(), 'Business types fetched successfully');
    }
}
