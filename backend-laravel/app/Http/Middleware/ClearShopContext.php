<?php

namespace App\Http\Middleware;

use App\Support\ShopContext;
use Closure;
use Illuminate\Http\Request;

class ClearShopContext
{
    public function handle(Request $request, Closure $next)
    {
        try {
            return $next($request);
        } finally {
            ShopContext::clear();
        }
    }
}
