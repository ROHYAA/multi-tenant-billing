<?php

namespace App\Http\Controllers\Api\V1;

use App\Exceptions\ApiException;
use App\Http\Controllers\Controller;
use App\Http\Requests\ForgotPasswordRequest;
use App\Http\Requests\LoginRequest;
use App\Http\Requests\ResetPasswordRequest;
use App\Http\Requests\SignupRequest;
use App\Http\Requests\TenantResolutionRequest;
use App\Services\AuthService;
use App\Services\LoginRateLimiter;
use App\Services\PasswordResetService;
use App\Services\SignupService;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class AuthController extends Controller
{
    public function __construct(
        private AuthService $auth,
        private SignupService $signup,
        private LoginRateLimiter $limiter,
        private PasswordResetService $passwords,
    ) {}

    public function signup(SignupRequest $request)
    {
        return $this->signup->signup($request->validated());
    }

    public function tenants(TenantResolutionRequest $request)
    {
        return ApiResponse::success(
            $this->auth->resolveTenantsForEmail($request->validated()['email']),
            'Tenants resolved'
        );
    }

    public function forgotPassword(ForgotPasswordRequest $request)
    {
        $data = $request->validated();
        $this->passwords->request($data['tenantSlug'], $data['email']);

        return ApiResponse::success(null, 'If that email is registered, a reset link has been sent.');
    }

    public function resetPassword(ResetPasswordRequest $request)
    {
        $data = $request->validated();
        $this->passwords->reset($data['tenantSlug'], $data['token'], $data['newPassword']);

        return ApiResponse::success(null, 'Password reset successfully. Please log in.');
    }

    public function login(LoginRequest $request)
    {
        $ip = $request->ip() ?? '0.0.0.0';
        $this->limiter->checkBlocked($ip);
        try {
            $response = $this->auth->login($request->validated(), $request);
            $this->limiter->clear($ip);

            return $response;
        } catch (ApiException $e) {
            if ($e->errorCode === 'AUTH_1001') {
                $this->limiter->recordFailure($ip);
            }
            throw $e;
        }
    }

    public function refresh(Request $request)
    {
        return $this->auth->refresh($request);
    }

    public function logout(Request $request)
    {
        return $this->auth->logout($request);
    }

    public function me()
    {
        return response()->json($this->auth->me());
    }
}
