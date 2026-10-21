<?php

namespace Tests\Feature;

use App\Models\PlatformAdmin;
use App\Models\Shop;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Cache;
use Tests\TestCase;

class Phase3AuthAdminTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_tenants_endpoint_is_public_and_does_not_enumerate_unknown_email(): void
    {
        $this->postJson('/api/v1/auth/tenants', ['email' => 'nobody@example.com'])
            ->assertOk()
            ->assertJsonPath('success', true)
            ->assertJsonPath('data.tenants', []);
    }

    public function test_tenants_endpoint_returns_slug_for_owner_email(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Rohit Store',
            'email' => 'owner@example.com',
            'password' => 'Secret123',
        ])->assertCreated();

        $this->postJson('/api/v1/auth/tenants', ['email' => 'owner@example.com'])
            ->assertOk()
            ->assertJsonPath('data.tenants.0.slug', 'owner')
            ->assertJsonPath('data.tenants.0.name', 'Rohit Store');
    }

    public function test_forgot_password_unknown_slug_is_404(): void
    {
        $this->postJson('/api/v1/auth/forgot-password', [
            'tenantSlug' => 'missing',
            'email' => 'a@example.com',
        ])->assertStatus(404)
            ->assertJsonPath('errorCode', 'TNT_2001');
    }

    public function test_forgot_password_unknown_email_is_200(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ]);

        $this->postJson('/api/v1/auth/forgot-password', [
            'tenantSlug' => 'ax',
            'email' => 'other@example.com',
        ])->assertOk()
            ->assertJsonPath('success', true);
    }

    public function test_reset_password_then_login_with_new_password(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ]);

        $this->postJson('/api/v1/auth/forgot-password', [
            'tenantSlug' => 'ax',
            'email' => 'a@example.com',
        ])->assertOk();

        $token = Cache::get('test.password_reset_token');
        $this->assertNotEmpty($token);

        $this->postJson('/api/v1/auth/reset-password', [
            'tenantSlug' => 'ax',
            'token' => $token,
            'newPassword' => 'NewSecret1',
        ])->assertOk();

        $this->postJson('/api/v1/auth/login', [
            'email' => 'a@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'ax',
        ])->assertStatus(401);

        $this->postJson('/api/v1/auth/login', [
            'email' => 'a@example.com',
            'password' => 'NewSecret1',
            'tenantSlug' => 'ax',
        ])->assertOk();
    }

    public function test_owner_can_read_tenant_during_trial(): void
    {
        $signup = $this->postJson('/api/v1/auth/signup', [
            'name' => 'Rohit Store',
            'email' => 'owner@example.com',
            'password' => 'Secret123',
        ]);
        $token = $signup->getCookie('access_token', false)?->getValue();

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/tenant')
            ->assertOk()
            ->assertJsonPath('data.status', 'ACTIVE')
            ->assertJsonPath('data.planName', 'FREE')
            ->assertJsonPath('data.name', 'Rohit Store');
    }

    public function test_admin_approve_and_409_when_not_pending(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Rohit Store',
            'email' => 'owner@example.com',
            'password' => 'Secret123',
        ]);
        $shop = Shop::query()->where('owner_email', 'owner@example.com')->first();
        $adminToken = $this->adminToken();
        $expires = now('UTC')->addYear()->toISOString();

        $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->getJson('/api/v1/admin/tenants')
            ->assertOk()
            ->assertJsonPath('data.content.0.status', 'ACTIVE')
            ->assertJsonPath('data.content.0.planName', 'FREE')
            ->assertJsonPath('data.page', 0);

        $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->postJson('/api/v1/admin/tenants/'.$shop->id.'/approve', [
                'planName' => 'YEARLY',
                'subscriptionExpiresAt' => $expires,
            ])
            ->assertStatus(409)
            ->assertJsonPath('errorCode', 'TNT_2009');

        $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->postJson('/api/v1/admin/tenants/'.$shop->id.'/lifetime')
            ->assertOk()
            ->assertJsonPath('data.status', 'ACTIVE')
            ->assertJsonPath('data.planName', 'LIFETIME');

        $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->postJson('/api/v1/admin/tenants/'.$shop->id.'/lifetime')
            ->assertStatus(409)
            ->assertJsonPath('errorCode', 'TNT_2011');

        $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->postJson('/api/v1/admin/tenants/'.$shop->id.'/reactivate', [
                'planName' => 'YEARLY',
                'subscriptionExpiresAt' => $expires,
            ])
            ->assertStatus(409)
            ->assertJsonPath('errorCode', 'TNT_2010');

        $owner = $this->postJson('/api/v1/auth/login', [
            'email' => 'owner@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'owner',
        ]);
        $ownerToken = $owner->getCookie('access_token', false)?->getValue();
        $this->withHeader('Authorization', 'Bearer '.$ownerToken)
            ->postJson('/api/v1/customers', ['name' => 'Acme'])
            ->assertCreated()
            ->assertJsonPath('data.name', 'Acme');
    }

    public function test_tenant_user_cannot_call_admin_apis(): void
    {
        $signup = $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ]);
        $token = $signup->getCookie('access_token', false)?->getValue();

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/admin/tenants')
            ->assertStatus(403)
            ->assertJsonPath('errorCode', 'AUTH_1003');
    }

    public function test_admin_refresh_rotates_cookie(): void
    {
        $this->ensureAdmin();
        $login = $this->postJson('/api/v1/admin/auth/login', [
            'email' => 'admin@platform.test',
            'password' => 'AdminPass1',
        ]);
        $login->assertOk();
        $refresh = $login->getCookie('refresh_token', false)?->getValue();
        $this->assertNotEmpty($refresh);

        $again = $this->postJson('/api/v1/admin/auth/refresh', [
            'refreshToken' => $refresh,
        ]);
        $again->assertOk()->assertJsonPath('data.user.role', 'SUPER_ADMIN');
        $this->assertNotEquals($refresh, $again->getCookie('refresh_token', false)?->getValue());
    }

    public function test_admin_audit_logs_and_remove_user(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ]);
        $shop = Shop::query()->where('owner_email', 'a@example.com')->first();
        $adminToken = $this->adminToken();

        $users = $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->getJson('/api/v1/admin/tenants/'.$shop->id.'/users')
            ->assertOk();
        $userId = $users->json('data.content.0.id');

        $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->getJson('/api/v1/admin/audit-logs')
            ->assertOk()
            ->assertJsonPath('success', true);

        $this->withHeader('Authorization', 'Bearer '.$adminToken)
            ->deleteJson('/api/v1/admin/users/tenant/'.$shop->id.'/user/'.$userId)
            ->assertOk();
    }

    private function adminToken(): string
    {
        $this->ensureAdmin();
        $login = $this->postJson('/api/v1/admin/auth/login', [
            'email' => 'admin@platform.test',
            'password' => 'AdminPass1',
        ]);
        $login->assertOk();

        return (string) $login->getCookie('access_token', false)?->getValue();
    }

    private function ensureAdmin(): void
    {
        if (PlatformAdmin::query()->where('email', 'admin@platform.test')->exists()) {
            return;
        }
        PlatformAdmin::query()->create([
            'email' => 'admin@platform.test',
            'password' => 'AdminPass1',
            'name' => 'Platform Admin',
            'role' => 'SUPER_ADMIN',
            'active' => true,
        ]);
    }
}
