<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\AdminLoginRequest;
use App\Services\AuthCookieService;
use App\Services\AuthService;
use App\Services\LoginRateLimiter;
use App\Exceptions\ApiException;
use Illuminate\Http\Request;

class AdminAuthController extends Controller
{
    public function __construct(
        private AuthService $auth,
        private AuthCookieService $cookies,
        private LoginRateLimiter $limiter,
    ) {}

    public function refresh(Request $request)
    {
        return $this->auth->adminRefresh($request);
    }

    public function login(AdminLoginRequest $request)
    {
        $ip = $request->ip() ?? '0.0.0.0';
        $this->limiter->checkBlocked($ip);
        try {
            $response = $this->auth->adminLogin($request->validated());
            $this->limiter->clear($ip);

            return $response;
        } catch (ApiException $e) {
            if ($e->errorCode === 'AUTH_1001') {
                $this->limiter->recordFailure($ip);
            }
            throw $e;
        }
    }

    public function logout(Request $request)
    {
        return $this->cookies->clear($this->auth->logout($request));
    }

    public function me()
    {
        return response()->json($this->auth->adminMe());
    }
}
