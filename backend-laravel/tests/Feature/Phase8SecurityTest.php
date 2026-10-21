<?php

namespace Tests\Feature;

use App\Models\Customer;
use App\Models\Shop;
use App\Models\User;
use App\Support\ShopContext;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Config;
use Tests\Concerns\CreatesApprovedShop;
use Tests\TestCase;

class Phase8SecurityTest extends TestCase
{
    use CreatesApprovedShop, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_refresh_and_logout_cookies(): void
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
        ])->assertOk();

        $refresh = $login->getCookie('refresh_token', false)?->getValue();
        $this->assertNotEmpty($refresh);
        $login->assertCookie('refresh_token');

        $refreshed = $this->postJson('/api/v1/auth/refresh', [
            'tenantSlug' => 'owner',
            'refreshToken' => $refresh,
        ])->assertOk()->assertCookie('access_token');

        $access = $refreshed->getCookie('access_token', false)?->getValue();
        $newRefresh = $refreshed->getCookie('refresh_token', false)?->getValue();
        $this->withHeader('Authorization', 'Bearer '.$access)
            ->postJson('/api/v1/auth/logout', ['refreshToken' => $newRefresh])
            ->assertOk();
    }

    public function test_marker_isolation_across_tenant_resources(): void
    {
        $tokenA = $this->approvedOwnerToken('a@example.com', 'Shop A');
        $tokenB = $this->approvedOwnerToken('b@example.com', 'Shop B');
        $marker = 'MARKER-A-'.uniqid();

        $custA = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/customers', ['name' => $marker])
            ->assertCreated()
            ->json('data.id');

        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->getJson('/api/v1/customers?search='.$marker)
            ->assertOk();
        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->getJson('/api/v1/customers/'.$custA)
            ->assertStatus(404);

        $prodA = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/products', ['name' => $marker, 'price' => 10])
            ->assertCreated()
            ->json('data.id');
        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->getJson('/api/v1/products/'.$prodA)
            ->assertStatus(404);

        $billA = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-invoices', [
                'customerId' => $custA,
                'notes' => $marker,
                'items' => [['description' => $marker, 'quantity' => 1, 'unitPrice' => 50]],
            ])
            ->assertCreated()
            ->json('data');
        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-invoices/'.$billA['id'].'/finalize')
            ->assertOk();

        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->getJson('/api/v1/business-invoices/'.$billA['id'])
            ->assertStatus(404);

        $payA = $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/business-payments/'.$billA['id'], [
                'amount' => 10,
                'method' => 'CASH',
            ])
            ->assertCreated()
            ->json('data.id');
        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->getJson('/api/v1/business-payments/invoice/'.$billA['id'])
            ->assertStatus(404);
        $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->patchJson('/api/v1/business-payments/'.$payA.'/confirm')
            ->assertStatus(404);

        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->putJson('/api/v1/shop-settings', ['businessName' => $marker])
            ->assertOk()
            ->assertJsonPath('data.businessName', $marker);
        $otherName = $this->withHeader('Authorization', 'Bearer '.$tokenB)
            ->getJson('/api/v1/shop-settings')
            ->assertOk()
            ->json('data.businessName');
        $this->assertNotSame($marker, $otherName);
    }

    public function test_client_shop_id_cannot_reassign_a_row(): void
    {
        $tokenA = $this->approvedOwnerToken('a@example.com', 'Shop A');
        $this->approvedOwnerToken('b@example.com', 'Shop B');
        $shopA = Shop::query()->where('owner_email', 'a@example.com')->first();
        $shopB = Shop::query()->where('owner_email', 'b@example.com')->first();
        $userA = User::withoutGlobalScopes()->where('shop_id', $shopA->id)->first();

        ShopContext::setUser($userA, $userA->permissionNames());
        $customer = Customer::query()->create([
            'shop_id' => $shopB->id,
            'name' => 'Hijack',
            'deleted' => false,
            'version' => 0,
        ]);
        $this->assertSame((int) $shopA->id, (int) $customer->shop_id);

        $this->withHeader('Authorization', 'Bearer '.$tokenA)
            ->postJson('/api/v1/customers', [
                'name' => 'Via API',
                'shop_id' => $shopB->id,
                'shopId' => $shopB->id,
                'tenantId' => $shopB->id,
            ])
            ->assertCreated();
        $this->assertSame(
            0,
            Customer::withoutGlobalScopes()->where('shop_id', $shopB->id)->where('name', 'Via API')->count()
        );
        $this->assertSame(
            1,
            Customer::withoutGlobalScopes()->where('shop_id', $shopA->id)->where('name', 'Via API')->count()
        );
    }

    public function test_employee_permission_matrix_and_tenant_cannot_mutate_permission_catalog(): void
    {
        $owner = $this->approvedOwnerToken('owner@example.com', 'Rohit Store');
        $this->withHeader('Authorization', 'Bearer '.$owner)
            ->postJson('/api/v1/users', [
                'name' => 'Clerk',
                'email' => 'clerk@example.com',
                'password' => 'Secret123',
                'roleName' => 'EMPLOYEE',
            ])
            ->assertCreated();

        $login = $this->postJson('/api/v1/auth/login', [
            'email' => 'clerk@example.com',
            'password' => 'Secret123',
            'tenantSlug' => 'owner',
        ])->assertOk();
        $clerk = $login->getCookie('access_token', false)?->getValue();

        $this->withHeader('Authorization', 'Bearer '.$clerk)
            ->postJson('/api/v1/customers', ['name' => 'Walk-in sale'])
            ->assertSuccessful();

        $this->withHeader('Authorization', 'Bearer '.$clerk)
            ->postJson('/api/v1/users', [
                'name' => 'Other',
                'email' => 'other@example.com',
                'password' => 'Secret123',
                'roleName' => 'EMPLOYEE',
            ])
            ->assertStatus(403);

        $this->withHeader('Authorization', 'Bearer '.$owner)
            ->postJson('/api/v1/permissions', ['name' => 'HACK'])
            ->assertStatus(403);
    }

    public function test_password_change_revokes_old_access_token(): void
    {
        $token = $this->approvedOwnerToken();
        $this->withHeader('Authorization', 'Bearer '.$token)
            ->putJson('/api/v1/users/me', ['password' => 'NewerPass1'])
            ->assertOk();

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->getJson('/api/v1/auth/me')
            ->assertStatus(401);
    }

    public function test_login_lockout_after_failures(): void
    {
        Config::set('shopledger.login_max_attempts', 5);
        Cache::flush();
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Shop',
            'email' => 'a@example.com',
            'password' => 'Secret123',
        ]);

        for ($i = 0; $i < 4; $i++) {
            $this->postJson('/api/v1/auth/login', [
                'email' => 'a@example.com',
                'password' => 'wrong-password',
                'tenantSlug' => 'ax',
            ])->assertStatus(401);
        }

        $this->postJson('/api/v1/auth/login', [
            'email' => 'a@example.com',
            'password' => 'wrong-password',
            'tenantSlug' => 'ax',
        ])->assertStatus(429)
            ->assertJsonPath('errorCode', 'AUTH_1009');
    }

    public function test_duplicate_finalize_overpay_and_monotonic_invoice_numbers(): void
    {
        $token = $this->approvedOwnerToken();
        $customer = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/customers', ['name' => 'Acme'])
            ->json('data.id');

        $first = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices', [
                'customerId' => $customer,
                'items' => [['description' => 'A', 'quantity' => 1, 'unitPrice' => 10]],
            ])->json('data');
        $second = $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices', [
                'customerId' => $customer,
                'items' => [['description' => 'B', 'quantity' => 1, 'unitPrice' => 10]],
            ])->json('data');

        $this->assertSame('INV-0001', $first['invoiceNumber']);
        $this->assertSame('INV-0002', $second['invoiceNumber']);

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices/'.$first['id'].'/finalize')
            ->assertOk();
        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-invoices/'.$first['id'].'/finalize')
            ->assertStatus(400)
            ->assertJsonPath('errorCode', 'RES_4004');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->postJson('/api/v1/business-payments/'.$first['id'], [
                'amount' => 99,
                'method' => 'CASH',
            ])
            ->assertStatus(400)
            ->assertJsonPath('errorCode', 'RES_4004');
    }
}
