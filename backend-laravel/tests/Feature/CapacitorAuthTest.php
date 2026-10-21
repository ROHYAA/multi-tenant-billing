<?php

namespace Tests\Feature;

use App\Models\Shop;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class CapacitorAuthTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_browser_login_does_not_put_jwt_in_json(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Rohit Store',
            'email' => 'owner@example.com',
            'password' => 'Secret123',
        ])->assertCreated();

        Shop::query()->where('owner_email', 'owner@example.com')->update(['status' => 'ACTIVE']);

        $login = $this->postJson('/api/v1/auth/login', [
            'email' => 'owner@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'owner',
        ]);

        $login->assertOk();
        $this->assertArrayNotHasKey('tokens', $login->json('data'));
        $login->assertCookie('access_token');
    }

    public function test_native_login_returns_bearer_tokens_and_me_works_without_relying_on_body_cookies(): void
    {
        $this->withHeader('X-ShopLedger-Client', 'capacitor')
            ->postJson('/api/v1/auth/signup', [
                'name' => 'Rohit Store',
                'email' => 'owner@example.com',
                'password' => 'Secret123',
            ])
            ->assertCreated()
            ->assertJsonPath('data.tokens.tokenType', 'Bearer');

        Shop::query()->where('owner_email', 'owner@example.com')->update(['status' => 'ACTIVE']);

        $login = $this->withHeader('X-ShopLedger-Client', 'capacitor')
            ->postJson('/api/v1/auth/login', [
                'email' => 'owner@example.com',
                'password' => 'Secret123',
                'tenantSlug' => 'owner',
            ])
            ->assertOk();

        $access = $login->json('data.tokens.accessToken');
        $refresh = $login->json('data.tokens.refreshToken');
        $this->assertNotEmpty($access);
        $this->assertNotEmpty($refresh);

        $this->withHeaders([
            'Authorization' => 'Bearer '.$access,
            'X-ShopLedger-Client' => 'capacitor',
        ])->getJson('/api/v1/auth/me')
            ->assertOk()
            ->assertJsonPath('data.email', 'owner@example.com');

        $refreshed = $this->withHeaders(['X-ShopLedger-Client' => 'capacitor'])
            ->postJson('/api/v1/auth/refresh', [
                'tenantSlug' => 'owner',
                'refreshToken' => $refresh,
            ])
            ->assertOk();

        $this->assertNotEmpty($refreshed->json('data.tokens.accessToken'));
        $this->assertNotSame($refresh, $refreshed->json('data.tokens.refreshToken'));
    }
}
