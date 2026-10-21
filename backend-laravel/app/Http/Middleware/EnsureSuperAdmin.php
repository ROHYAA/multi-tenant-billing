<?php

namespace App\Http\Middleware;

use App\Support\ApiResponse;
use App\Support\ShopContext;
use Closure;
use Illuminate\Http\Request;

class EnsureSuperAdmin
{
    public function handle(Request $request, Closure $next)
    {
        if (! ShopContext::isSuperAdmin()) {
            return ApiResponse::error('Access denied', 'AUTH_1003', 403);
        }

        return $next($request);
    }
}
