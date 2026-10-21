<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Shop;
use App\Models\User;
use App\Support\NativeClient;
use App\Support\ShopContext;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class SignupService
{
    public function __construct(
        private ShopProvisioningService $provisioning,
        private JwtTokenService $jwt,
        private AuthCookieService $cookies,
        private AuditLogger $audit,
        private BusinessTypeCatalog $businessTypes,
    ) {}

    public function signup(array $data)
    {
        if (Shop::query()->where('owner_email', $data['email'])->exists()) {
            throw ApiException::emailExists();
        }
        $this->businessTypes->assertActive($data['businessType'] ?? null);

        $slugBase = $this->slugFromEmail($data['email']);
        $slug = $slugBase;
        $i = 0;
        while (Shop::query()->where('slug', $slug)->exists()) {
            $i++;
            $slug = $slugBase.'-'.$i;
        }

        $shop = null;
        $user = null;

        DB::transaction(function () use ($data, $slug, &$shop, &$user) {
            $shop = Shop::query()->create([
                'name' => $data['name'],
                'schema_name' => 'schema_'.$slug.'_'.(int) (microtime(true) * 1000),
                'slug' => $slug,
                'status' => 'ACTIVE',
                'plan_name' => 'FREE',
                'subscription_expires_at' => null,
                'owner_email' => $data['email'],
                'deleted' => false,
                'version' => 0,
            ]);

            $adminRole = $this->provisioning->provision($shop, $data['businessType'] ?? null);

            $user = User::withoutGlobalScopes()->create([
                'shop_id' => $shop->id,
                'name' => $data['name'],
                'email' => $data['email'],
                'password' => $data['password'],
                'role_id' => $adminRole->id,
                'status' => 'ACTIVE',
                'token_version' => 1,
                'is_first_login' => true,
                'deleted' => false,
                'version' => 0,
            ]);
        });

        $user = $user->fresh();
        $user->loadMissing('role');
        $shop = $shop->fresh();
        ShopContext::setUser($user, $user->permissionNames());

        $issued = $this->issue($user);

        $this->audit->log('CREATE', 'TENANT', $shop->id, 'Shop signup', ['slug' => $shop->slug], 'TENANT_MANAGEMENT');

        $payload = [
            'success' => true,
            'message' => 'Account created successfully.',
            'data' => [
                'user' => [
                    'userId' => $user->id,
                    'email' => $user->email,
                    'role' => $user->role?->name ?? 'ADMIN',
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
                    'issuedAt' => now('UTC')->toISOString(),
                    'expiresAt' => now('UTC')->addSeconds($this->jwt->accessTtlSeconds())->toISOString(),
                    'isFirstLogin' => true,
                ],
                'flags' => [
                    'isTrial' => false,
                    'requiresOnboarding' => false,
                ],
            ],
            'timestamp' => now('UTC')->toISOString(),
        ];

        return $this->cookies->addTenantCookies(
            response()->json(NativeClient::withTokens($payload, $issued['access'], $issued['refresh']), 201),
            $issued['access'],
            $issued['refresh'],
        );
    }

    private function issue(User $user): array
    {
        $access = $this->jwt->generateAccessToken($user);
        $refresh = (string) Str::uuid();
        \App\Models\RefreshToken::withoutGlobalScopes()->create([
            'shop_id' => $user->shop_id,
            'user_id' => $user->id,
            'token' => $refresh,
            'expiry_date' => now()->addSeconds($this->jwt->refreshTtlSeconds()),
            'revoked' => false,
            'deleted' => false,
            'version' => 0,
        ]);

        return ['access' => $access, 'refresh' => $refresh];
    }

    private function slugFromEmail(string $email): string
    {
        $local = strtolower(preg_replace('/[^a-zA-Z0-9]/', '', explode('@', $email)[0]) ?? '');
        $local = $local !== '' ? $local : 'shop';
        if (strlen($local) < 2) {
            $local = str_pad($local, 2, 'x');
        }

        return substr($local, 0, 20);
    }
}
