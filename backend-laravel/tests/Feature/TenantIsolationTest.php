<?php

namespace Tests\Feature;

use App\Models\Customer;
use App\Models\Shop;
use App\Models\User;
use App\Support\ShopContext;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class TenantIsolationTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_two_shops_cannot_see_each_others_customers(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop A',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ])->assertCreated();

        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop B',
            'email' => 'b@example.com',
            'password' => 'Secret123',
        ])->assertCreated();

        $shopA = Shop::query()->where('owner_email', 'a@example.com')->first();
        $shopB = Shop::query()->where('owner_email', 'b@example.com')->first();
        $userA = User::withoutGlobalScopes()->where('shop_id', $shopA->id)->first();
        $userB = User::withoutGlobalScopes()->where('shop_id', $shopB->id)->first();

        $marker = 'SECRET-A-'.uniqid();
        Customer::withoutGlobalScopes()->create([
            'shop_id' => $shopA->id,
            'name' => $marker,
            'deleted' => false,
            'version' => 0,
        ]);

        ShopContext::setUser($userB, $userB->permissionNames());
        $this->assertFalse(
            Customer::query()->where('name', $marker)->exists(),
            'Shop B global scope must not return Shop A customer'
        );

        ShopContext::setUser($userA, $userA->permissionNames());
        $this->assertTrue(Customer::query()->where('name', $marker)->exists());

        $meA = $this->postJson('/api/v1/auth/login', [
            'email' => 'a@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'ax',
        ]);
        $meB = $this->postJson('/api/v1/auth/login', [
            'email' => 'b@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'bx',
        ]);

        $this->assertNotEquals(
            $meA->json('data.tenant.tenantId'),
            $meB->json('data.tenant.tenantId')
        );
    }

    public function test_user_cannot_login_to_another_shop_slug(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop A',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ]);
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop B',
            'email' => 'b@example.com',
            'password' => 'Secret123',
        ]);

        $this->postJson('/api/v1/auth/login', [
            'email' => 'a@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'bx',
        ])->assertStatus(401);
    }
}
