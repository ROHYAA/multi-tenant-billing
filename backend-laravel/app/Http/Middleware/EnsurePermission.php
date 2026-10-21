<?php

namespace App\Http\Middleware;

use App\Support\ApiResponse;
use App\Support\ShopContext;
use Closure;
use Illuminate\Http\Request;

class EnsurePermission
{
    public function handle(Request $request, Closure $next, string $permission)
    {
        if (ShopContext::isSuperAdmin()) {
            return $next($request);
        }
        if (! ShopContext::hasPermission($permission)) {
            return ApiResponse::error('Access denied', 'AUTH_1003', 403);
        }

        return $next($request);
    }
}
