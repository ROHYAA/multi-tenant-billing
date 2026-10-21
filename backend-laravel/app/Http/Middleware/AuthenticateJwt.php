<?php

namespace App\Http\Middleware;

use App\Exceptions\ApiException;
use App\Models\PlatformAdmin;
use App\Models\Shop;
use App\Models\User;
use App\Services\AuthCookieService;
use App\Services\JwtTokenService;
use App\Support\ApiResponse;
use App\Support\ShopContext;
use Closure;
use Illuminate\Http\Request;

class AuthenticateJwt
{
    public function __construct(
        private JwtTokenService $jwt,
        private AuthCookieService $cookies,
    ) {}

    public function handle(Request $request, Closure $next)
    {
        ShopContext::clear();
        $token = $this->cookies->accessToken($request);
        if (! $token) {
            return ApiResponse::error('Unauthorized', 'UNAUTHORIZED', 401);
        }

        $claims = $this->jwt->tryDecode($token);
        if (! $claims) {
            return ApiResponse::error('Unauthorized', 'UNAUTHORIZED', 401);
        }

        try {
            if (! empty($claims->isSuperAdmin)) {
                $admin = PlatformAdmin::query()->find($claims->sub);
                if (! $admin || ! $admin->active) {
                    return ApiResponse::error('Unauthorized', 'UNAUTHORIZED', 401);
                }
                ShopContext::setAdmin($admin);

                return $next($request);
            }

            $user = User::withoutGlobalScopes()->where('deleted', false)->find($claims->sub);
            if (! $user) {
                return ApiResponse::error('Unauthorized', 'UNAUTHORIZED', 401);
            }
            $shop = Shop::query()->find($claims->tenantId ?? $user->shop_id);
            if (! $shop) {
                return ApiResponse::error('Unauthorized', 'UNAUTHORIZED', 401);
            }
            if ((int) ($claims->tokenVersion ?? 0) !== (int) $user->token_version) {
                throw ApiException::tokenRevoked();
            }

            ShopContext::setUser($user, $user->permissionNames());

            $write = ! in_array($request->method(), ['GET', 'HEAD', 'OPTIONS'], true);
            $authLifecycle = str_starts_with($request->path(), 'api/v1/auth/');
            if ($write && ! $authLifecycle && $shop->isWriteBlocked()) {
                return ApiResponse::error(
                    $shop->writeBlockMessage(),
                    $shop->status === 'SUSPENDED' ? 'TENANT_SUSPENDED' : 'TENANT_PENDING_APPROVAL',
                    403
                );
            }

            return $next($request);
        } catch (ApiException $e) {
            ShopContext::clear();

            return ApiResponse::error($e->getMessage(), $e->errorCode, $e->httpStatus);
        } finally {
            // ShopContext is cleared by Terminate middleware below
        }
    }
}
