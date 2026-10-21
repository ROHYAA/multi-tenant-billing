<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Shop;
use App\Models\User;
use App\Support\ShopContext;

class ShopSelfService
{
    public function __construct(private AuditLogger $audit) {}

    public function current(): array
    {
        return $this->toShopResponse($this->shop());
    }

    public function update(array $data): array
    {
        $shop = $this->shop();
        if (isset($data['name'])) {
            $shop->name = $data['name'];
            $shop->save();
        }
        $this->audit->log('UPDATE', 'TENANT', $shop->id, 'Shop updated', ['name' => $shop->name], 'TENANT_MANAGEMENT');

        return $this->toShopResponse($shop);
    }

    public function schemaInfo(): array
    {
        $shop = $this->shop();
        $users = User::withoutGlobalScopes()->where('shop_id', $shop->id)->where('deleted', false)->count();
        $roles = \App\Models\Role::withoutGlobalScopes()->where('shop_id', $shop->id)->where('deleted', false)->count();

        return [
            'schemaName' => $shop->schema_name,
            'userCount' => $users,
            'roleCount' => $roles,
            'createdAt' => optional($shop->created_at)?->toISOString(),
        ];
    }

    public function status(): array
    {
        $shop = $this->shop();

        return [
            'tenantStatus' => $shop->status,
            'isSuspended' => $shop->status !== 'ACTIVE',
        ];
    }

    public function deactivate(): void
    {
        $this->assertOwner();
        $shop = $this->shop();
        $shop->status = 'INACTIVE';
        $shop->save();
        $this->audit->log('STATUS_CHANGE', 'TENANT', $shop->id, 'Shop deactivated by owner', ['status' => 'INACTIVE'], 'TENANT_MANAGEMENT');
    }

    public function reactivate(): void
    {
        $shop = $this->shop();
        $shop->status = 'ACTIVE';
        $shop->save();
        $this->audit->log('STATUS_CHANGE', 'TENANT', $shop->id, 'Shop reactivated', ['status' => 'ACTIVE'], 'TENANT_MANAGEMENT');
    }

    public function toShopResponse(Shop $shop): array
    {
        return [
            'id' => $shop->id,
            'name' => $shop->name,
            'schemaName' => $shop->schema_name,
            'status' => $shop->status,
            'planName' => $shop->plan_name,
            'subscriptionExpiresAt' => optional($shop->subscription_expires_at)?->toISOString(),
            'createdAt' => optional($shop->created_at)?->toISOString(),
        ];
    }

    private function shop(): Shop
    {
        $shop = ShopContext::shop();
        if (! $shop) {
            throw ApiException::tenantNotFound();
        }

        return $shop;
    }

    private function assertOwner(): void
    {
        $role = ShopContext::user()?->role?->name;
        if ($role !== 'OWNER') {
            throw ApiException::accessDenied();
        }
    }
}
