<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\PlatformAdmin;
use App\Models\RefreshToken;
use App\Models\Shop;
use App\Models\User;
use App\Support\NativeClient;
use App\Support\ShopContext;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;

class AuthService
{
    public function __construct(
        private JwtTokenService $jwt,
        private AuthCookieService $cookies,
        private AuditLogger $audit,
    ) {}

    public function login(array $data, Request $request)
    {
        $shop = Shop::query()->where('slug', strtolower(trim($data['tenantSlug'])))->first();
        if (! $shop) {
            throw ApiException::invalidCredentials();
        }
        if (in_array($shop->status, ['SUSPENDED', 'INACTIVE'], true)) {
            throw ApiException::shopSuspended();
        }

        $user = User::withoutGlobalScopes()
            ->where('shop_id', $shop->id)
            ->where('email', $data['email'])
            ->where('deleted', false)
            ->first();

        if (! $user || ! Hash::check($data['password'], $user->password)) {
            throw ApiException::invalidCredentials();
        }
        if ($user->status !== 'ACTIVE') {
            throw ApiException::accessDenied('Account is disabled');
        }

        ShopContext::setUser($user, $user->permissionNames());
        $tokens = $this->issueTenantTokens($user);
        $response = $this->cookies->addTenantCookies(
            response()->json($this->authPayload($user, $shop, $tokens['issuedAt'], 'Login successful', $tokens['access'], $tokens['refresh'])),
            $tokens['access'],
            $tokens['refresh'],
        );

        $this->audit->log('LOGIN', 'USER', $user->id, 'Tenant user login');

        return $response;
    }

    public function refresh(Request $request)
    {
        $raw = $this->cookies->refreshToken($request);
        if (! $raw) {
            throw ApiException::invalidCredentials();
        }

        $slug = strtolower(trim((string) $request->input('tenantSlug', '')));
        $record = RefreshToken::withoutGlobalScopes()
            ->where('token', $raw)
            ->where('revoked', false)
            ->where('deleted', false)
            ->first();

        if (! $record || $record->expiry_date->isPast()) {
            throw ApiException::tokenRevoked();
        }

        $user = User::withoutGlobalScopes()->find($record->user_id);
        $shop = Shop::query()->find($record->shop_id);
        if (! $user || ! $shop) {
            throw ApiException::tokenRevoked();
        }
        if ($slug !== '' && $shop->slug !== $slug) {
            throw ApiException::invalidCredentials();
        }
        if (in_array($shop->status, ['SUSPENDED', 'INACTIVE'], true)) {
            throw ApiException::shopSuspended();
        }

        $record->revoked = true;
        $record->save();

        ShopContext::setUser($user, $user->permissionNames());
        $tokens = $this->issueTenantTokens($user);

        return $this->cookies->addTenantCookies(
            response()->json($this->authPayload($user, $shop, $tokens['issuedAt'], 'Token refreshed successfully', $tokens['access'], $tokens['refresh'])),
            $tokens['access'],
            $tokens['refresh'],
        );
    }

    public function logout(Request $request)
    {
        $raw = $this->cookies->refreshToken($request);
        if ($raw) {
            RefreshToken::withoutGlobalScopes()
                ->where('token', $raw)
                ->update(['revoked' => true]);
        }
        ShopContext::clear();

        return $this->cookies->clear(
            response()->json([
                'success' => true,
                'message' => 'Logged out successfully',
                'data' => null,
                'timestamp' => now('UTC')->toISOString(),
            ])
        );
    }

    public function me(): array
    {
        $user = ShopContext::user();
        $shop = ShopContext::shop();
        if (! $user || ! $shop) {
            throw ApiException::accessDenied();
        }

        return [
            'success' => true,
            'message' => 'Profile fetched successfully',
            'data' => [
                'userId' => $user->id,
                'name' => $user->name,
                'email' => $user->email,
                'role' => $user->role?->name,
                'status' => $user->status,
                'tenantId' => $shop->id,
                'tenantName' => $shop->name,
                'tenantStatus' => $shop->status,
                'planName' => $shop->plan_name,
                'subscriptionExpiresAt' => optional($shop->subscription_expires_at)?->toISOString(),
                'isTrial' => $shop->isTrialPlan() && ! $shop->subscriptionLapsed(),
                'schemaName' => $shop->schema_name,
                'permissions' => ShopContext::permissions(),
                'createdAt' => optional($user->created_at)?->toISOString(),
            ],
            'timestamp' => now('UTC')->toISOString(),
        ];
    }

    public function adminLogin(array $data)
    {
        $admin = PlatformAdmin::query()->where('email', $data['email'])->where('active', true)->first();
        if (! $admin || ! Hash::check($data['password'], $admin->password)) {
            throw ApiException::invalidCredentials();
        }

        ShopContext::setAdmin($admin);
        $tokens = $this->issueAdminTokens($admin);
        $access = $tokens['access'];
        $refresh = $tokens['refresh'];
        $issuedAt = Carbon::now('UTC');

        $payload = [
            'success' => true,
            'message' => 'Platform admin login successful',
            'data' => [
                'user' => [
                    'userId' => $admin->id,
                    'email' => $admin->email,
                    'role' => 'SUPER_ADMIN',
                    'permissions' => null,
                ],
                'tenant' => null,
                'session' => [
                    'issuedAt' => $issuedAt->toISOString(),
                    'expiresAt' => $issuedAt->copy()->addSeconds($this->jwt->accessTtlSeconds())->toISOString(),
                    'isFirstLogin' => false,
                ],
                'flags' => null,
            ],
            'timestamp' => $issuedAt->toISOString(),
        ];

        $this->audit->log('LOGIN', 'ADMIN', $admin->id, 'Platform admin login', [], 'ADMIN');

        return $this->cookies->addAdminCookies(
            response()->json(NativeClient::withTokens($payload, $access, $refresh)),
            $access,
            $refresh
        );
    }

    public function resolveTenantsForEmail(string $email): array
    {
        $tenants = Shop::query()
            ->whereRaw('LOWER(owner_email) = ?', [strtolower(trim($email))])
            ->get()
            ->map(fn (Shop $shop) => [
                'slug' => $shop->slug,
                'name' => $shop->name,
            ])
            ->values()
            ->all();

        return ['tenants' => $tenants];
    }

    public function adminRefresh(Request $request)
    {
        $raw = $this->cookies->refreshToken($request);
        if (! $raw) {
            throw ApiException::invalidCredentials();
        }

        $adminId = Cache::pull('super_admin_refresh:'.$raw);
        if (! $adminId) {
            throw ApiException::invalidCredentials();
        }

        $admin = PlatformAdmin::query()->find($adminId);
        if (! $admin || ! $admin->active) {
            throw ApiException::invalidCredentials();
        }

        ShopContext::setAdmin($admin);
        $tokens = $this->issueAdminTokens($admin);
        $issuedAt = Carbon::now('UTC');

        $payload = [
            'success' => true,
            'message' => 'Admin token refreshed successfully',
            'data' => [
                'user' => [
                    'userId' => $admin->id,
                    'email' => $admin->email,
                    'role' => 'SUPER_ADMIN',
                    'permissions' => null,
                ],
                'tenant' => null,
                'session' => [
                    'issuedAt' => $issuedAt->toISOString(),
                    'expiresAt' => $issuedAt->copy()->addSeconds($this->jwt->accessTtlSeconds())->toISOString(),
                    'isFirstLogin' => false,
                ],
                'flags' => null,
            ],
            'timestamp' => $issuedAt->toISOString(),
        ];

        $this->audit->log('LOGIN', 'USER', $admin->id, 'Super admin token refreshed with cookies', [], 'ADMIN_AUTH');

        return $this->cookies->addAdminCookies(
            response()->json(NativeClient::withTokens($payload, $tokens['access'], $tokens['refresh'])),
            $tokens['access'],
            $tokens['refresh']
        );
    }

    public function adminMe(): array
    {
        $admin = ShopContext::admin();
        if (! $admin) {
            throw ApiException::accessDenied();
        }

        return [
            'success' => true,
            'data' => [
                'userId' => $admin->id,
                'email' => $admin->email,
                'role' => $admin->role,
            ],
            'timestamp' => now('UTC')->toISOString(),
        ];
    }

    private function issueAdminTokens(PlatformAdmin $admin): array
    {
        $refresh = (string) Str::uuid();
        Cache::put(
            'super_admin_refresh:'.$refresh,
            $admin->id,
            $this->jwt->refreshTtlSeconds()
        );

        return [
            'access' => $this->jwt->generateAdminAccessToken($admin),
            'refresh' => $refresh,
        ];
    }

    private function issueTenantTokens(User $user): array
    {
        $access = $this->jwt->generateAccessToken($user);
        $refresh = (string) Str::uuid();
        RefreshToken::withoutGlobalScopes()->create([
            'shop_id' => $user->shop_id,
            'user_id' => $user->id,
            'token' => $refresh,
            'expiry_date' => now()->addSeconds($this->jwt->refreshTtlSeconds()),
            'revoked' => false,
            'deleted' => false,
            'version' => 0,
        ]);

        return [
            'access' => $access,
            'refresh' => $refresh,
            'issuedAt' => Carbon::now('UTC'),
        ];
    }

    private function authPayload(
        User $user,
        Shop $shop,
        Carbon $issuedAt,
        string $message = 'Login successful',
        ?string $access = null,
        ?string $refresh = null,
    ): array {
        $user->loadMissing('role');

        $payload = [
            'success' => true,
            'message' => $message,
            'data' => [
                'user' => [
                    'userId' => $user->id,
                    'email' => $user->email,
                    'role' => $user->role?->name,
                    'permissions' => $user->permissionNames(),
                ],
                'tenant' => [
                    'tenantId' => $shop->id,
                    'tenantName' => $shop->name,
                    'tenantStatus' => $shop->status,
                    'planName' => $shop->plan_name,
                    'subscriptionExpiresAt' => optional($shop->subscription_expires_at)?->toISOString(),
                ],
                'session' => [
                    'issuedAt' => $issuedAt->toISOString(),
                    'expiresAt' => $issuedAt->copy()->addSeconds($this->jwt->accessTtlSeconds())->toISOString(),
                    'isFirstLogin' => (bool) $user->is_first_login,
                ],
                'flags' => [
                    'isTrial' => $shop->isTrialPlan() && ! $shop->subscriptionLapsed(),
                    'requiresOnboarding' => false,
                ],
            ],
            'timestamp' => $issuedAt->toISOString(),
        ];

        if ($access !== null && $refresh !== null) {
            return NativeClient::withTokens($payload, $access, $refresh);
        }

        return $payload;
    }
}
