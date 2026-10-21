<?php

namespace Tests\Feature;

use App\Models\Shop;
use App\Models\ShopSetting;
use Database\Seeders\DatabaseSeeder;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\Concerns\CreatesApprovedShop;
use Tests\TestCase;

class BusinessTypeTest extends TestCase
{
    use CreatesApprovedShop, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(DatabaseSeeder::class);
    }

    public function test_catalog_is_public_and_keeps_legacy_codes(): void
    {
        $codes = $this->getJson('/api/v1/business-types')
            ->assertOk()
            ->json('data');

        $this->assertTrue(collect($codes)->contains(fn ($row) => $row['code'] === 'RETAIL'));
        $this->assertTrue(collect($codes)->contains(fn ($row) => $row['code'] === 'GROCERY'));
        $this->assertTrue(collect($codes)->contains(fn ($row) => $row['code'] === 'MOBILE_ELECTRONICS'));
        $this->assertTrue(collect($codes)->contains(fn ($row) => $row['code'] === 'SALON'));
        $this->assertTrue(collect($codes)->contains(fn ($row) => $row['code'] === 'REPAIR'));
        $this->assertTrue(collect($codes)->contains(fn ($row) => $row['code'] === 'OTHER'));
        $this->assertFalse(collect($codes)->contains(fn ($row) => $row['code'] === 'HOTEL_CAFE'));
    }

    public function test_signup_stores_business_type_without_remapping_retail(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Kirana',
            'email' => 'g@example.com',
            'password' => 'Secret123',
            'businessType' => 'GROCERY',
        ])->assertCreated();

        $groceryShop = Shop::query()->where('owner_email', 'g@example.com')->first();
        $this->assertSame(
            'GROCERY',
            ShopSetting::withoutGlobalScopes()->where('shop_id', $groceryShop->id)->value('business_type')
        );

        $this->postJson('/api/v1/auth/signup', [
            'name' => 'Mart',
            'email' => 'r@example.com',
            'password' => 'Secret123',
            'businessType' => 'RETAIL',
        ])->assertCreated();

        $retailShop = Shop::query()->where('owner_email', 'r@example.com')->first();
        $this->assertSame(
            'RETAIL',
            ShopSetting::withoutGlobalScopes()->where('shop_id', $retailShop->id)->value('business_type')
        );
    }

    public function test_unknown_business_type_is_rejected(): void
    {
        $this->postJson('/api/v1/auth/signup', [
            'name' => 'X',
            'email' => 'x@example.com',
            'password' => 'Secret123',
            'businessType' => 'KIRANA',
        ])->assertStatus(400)
            ->assertJsonPath('errorCode', 'RES_4004');

        $token = $this->approvedOwnerToken();
        $this->withHeader('Authorization', 'Bearer '.$token)
            ->putJson('/api/v1/shop-settings', ['businessType' => 'KIRANA'])
            ->assertStatus(400)
            ->assertJsonPath('errorCode', 'RES_4004');

        $this->withHeader('Authorization', 'Bearer '.$token)
            ->putJson('/api/v1/shop-settings', ['businessType' => 'SALON'])
            ->assertOk()
            ->assertJsonPath('data.businessType', 'SALON');
    }
}
