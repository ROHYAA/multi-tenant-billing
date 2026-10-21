<?php

namespace Tests\Feature;

use App\Models\Shop;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class AuthTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_signup_sets_cookies_and_starts_a_free_shop(): void
    {
        $response = $this->postJson('/api/v1/auth/signup', [
            'name' => 'Rohit Store',
            'email' => 'owner@example.com',
            'password' => 'Secret123',
        ]);

        $response->assertCreated()
            ->assertJsonPath('success', true)
            ->assertJsonPath('data.tenant.tenantStatus', 'ACTIVE')
            ->assertJsonPath('data.tenant.planName', 'FREE')
            ->assertJsonPath('data.flags.isTrial', false)
            ->assertJsonPath('data.user.role', 'ADMIN');

        $this->assertNull($response->json('data.tenant.subscriptionExpiresAt'));
        $response->assertCookie('access_token');
    }

    public function test_login_and_me(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Rohit Store',
            'email' => 'owner@example.com',
            'password' => 'Secret123',
        ])->assertCreated();

        $login = $this->postJson('/api/v1/auth/login', [
            'email' => 'owner@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'owner',
        ]);

        $login->assertOk()->assertJsonPath('data.user.email', 'owner@example.com');
        $token = $login->getCookie('access_token', false)?->getValue();
        $this->assertNotEmpty($token);

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/auth/me')
            ->assertOk()
            ->assertJsonPath('data.email', 'owner@example.com')
            ->assertJsonPath('data.tenantStatus', 'ACTIVE')
            ->assertJsonPath('data.planName', 'FREE')
            ->assertJsonPath('data.isTrial', false);
    }

    public function test_validation_errors_use_400_and_field_errors(): void
    {
        $this->postJson('/api/v1/auth/login', [
            'email' => 'not-an-email',
            'password' => '',
            'tenantSlug' => 'A',
        ])->assertStatus(400)
            ->assertJsonPath('success', false)
            ->assertJsonPath('errorCode', 'VAL_6001')
            ->assertJsonStructure(['fieldErrors']);
    }

    public function test_trial_shop_can_write_customers(): void
    {
        $signup = $this->postJson('/api/v1/auth/signup', [
            'name' => 'Trial Shop',
            'email' => 'pending@example.com',
            'password' => 'Secret123',
        ])->assertCreated();

        $token = $signup->getCookie('access_token', false)?->getValue();

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/customers', ['name' => 'Ravi Trader', 'phone' => '9876543210'])
            ->assertSuccessful();
    }

    public function test_old_trial_dates_do_not_block_writes_on_the_free_plan(): void
    {
        $signup = $this->postJson('/api/v1/auth/signup', [
            'name' => 'Expired Shop',
            'email' => 'expired@example.com',
            'password' => 'Secret123',
        ])->assertCreated();

        Shop::query()->where('owner_email', 'expired@example.com')->update([
            'plan_name' => 'TRIAL',
            'subscription_expires_at' => now('UTC')->subDay(),
        ]);

        $token = $signup->getCookie('access_token', false)?->getValue();

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/customers', ['name' => 'Ravi Trader', 'phone' => '9876543210'])
            ->assertSuccessful();
    }

    public function test_wrong_password_is_401(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ]);

        $this->postJson('/api/v1/auth/login', [
            'email' => 'a@example.com',
            'password' => 'wrong-password',
            'tenantSlug' => 'ax',
        ])->assertStatus(401)
            ->assertJsonPath('errorCode', 'AUTH_1001');
    }
}
