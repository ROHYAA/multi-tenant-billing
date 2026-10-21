<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Support\ApiResponse;
use Illuminate\Support\Facades\DB;

class HealthController extends Controller
{
    public function __invoke()
    {
        $db = true;
        try {
            DB::connection()->getPdo();
        } catch (\Throwable) {
            $db = false;
        }

        return ApiResponse::success([
            'status' => $db ? 'UP' : 'DOWN',
            'database' => $db,
        ], 'OK', $db ? 200 : 503);
    }
}
