<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Shop;
use App\Models\User;
use App\Support\ApiResponse;
use App\Support\SpringPage;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;

class AdminTenantService
{
    public function __construct(
        private ShopSelfService $shops,
        private AuditLogger $audit,
    ) {}

    public function list(Request $request): array
    {
        $query = Shop::query();
        if ($status = $request->query('status')) {
            $query->where('status', $status);
        }
        [$rows, $page, $size, $total] = SpringPage::paginate($query, $request, 'created_at,desc');
        $content = $rows->map(fn (Shop $shop) => $this->toListItem($shop))->values()->all();

        return ApiResponse::page($content, $page, $size, $total);
    }

    public function detail(int $id): array
    {
        $shop = $this->find($id);
        $userCount = User::withoutGlobalScopes()->where('shop_id', $shop->id)->where('deleted', false)->count();

        return [
            'id' => $shop->id,
            'name' => $shop->name,
            'schemaName' => $shop->schema_name,
            'status' => $shop->status,
            'userCount' => $userCount,
            'createdAt' => optional($shop->created_at)?->toISOString(),
            'updatedAt' => optional($shop->updated_at)?->toISOString(),
        ];
    }

    public function changeStatus(int $id, string $status, string $reason): array
    {
        $shop = $this->find($id);
        $shop->status = $status;
        $shop->save();
        $this->audit->log('STATUS_CHANGE', 'TENANT', $shop->id, 'Admin changed tenant status: '.$reason, ['status' => $status], 'ADMIN_TENANT_MANAGEMENT');

        return $this->shops->toShopResponse($shop);
    }

    public function approve(int $id, string $planName, string $expiresAt): array
    {
        $shop = $this->find($id);
        if ($shop->status !== 'PENDING_APPROVAL') {
            throw ApiException::notPendingApproval();
        }
        $this->activateWithPlan($shop, $planName, $expiresAt);
        $this->audit->log('STATUS_CHANGE', 'TENANT', $shop->id, 'Admin approved newly signed-up shop — plan: '.$planName, [
            'status' => 'ACTIVE',
            'planName' => $planName,
        ], 'ADMIN_TENANT_MANAGEMENT');

        return $this->shops->toShopResponse($shop);
    }

    public function grantLifetime(int $id): array
    {
        $shop = $this->find($id);
        if ($shop->isLifetime() && $shop->status === 'ACTIVE') {
            throw ApiException::alreadyLifetime();
        }
        $shop->status = 'ACTIVE';
        $shop->plan_name = 'LIFETIME';
        $shop->subscription_expires_at = null;
        $shop->expiry_alert_sent_at = null;
        $shop->save();
        $this->audit->log('STATUS_CHANGE', 'TENANT', $shop->id, 'Admin granted ₹150 lifetime after UPI screenshot', [
            'status' => 'ACTIVE',
            'planName' => 'LIFETIME',
        ], 'ADMIN_TENANT_MANAGEMENT');

        return $this->shops->toShopResponse($shop);
    }

    public function reactivate(int $id, string $planName, string $expiresAt): array
    {
        $shop = $this->find($id);
        if ($shop->status !== 'SUSPENDED') {
            throw ApiException::notSuspended();
        }
        $this->activateWithPlan($shop, $planName, $expiresAt);
        $this->audit->log('STATUS_CHANGE', 'TENANT', $shop->id, 'Admin reactivated suspended shop — plan: '.$planName, [
            'status' => 'ACTIVE',
            'planName' => $planName,
        ], 'ADMIN_TENANT_MANAGEMENT');

        return $this->shops->toShopResponse($shop);
    }

    public function users(int $id, Request $request): array
    {
        $this->find($id);
        $query = User::withoutGlobalScopes()->where('shop_id', $id)->where('deleted', false);
        [$rows, $page, $size, $total] = SpringPage::paginate($query, $request, 'created_at,desc');
        $content = $rows->map(function (User $user) {
            $user->load('role');

            return [
                'id' => $user->id,
                'name' => $user->name,
                'email' => $user->email,
                'roleName' => $user->role?->name,
                'status' => $user->status,
                'createdAt' => optional($user->created_at)?->toISOString(),
                'updatedAt' => optional($user->updated_at)?->toISOString(),
            ];
        })->values()->all();

        return ApiResponse::page($content, $page, $size, $total);
    }

    public function delete(int $id): void
    {
        $shop = $this->find($id);
        $shop->deleted = true;
        $shop->deleted_at = now();
        $shop->save();
        $this->audit->log('DELETE', 'TENANT', $shop->id, 'Admin deleted tenant: '.$shop->name, [], 'ADMIN_TENANT_MANAGEMENT');
    }

    public function removeUser(int $tenantId, int $userId): void
    {
        $this->find($tenantId);
        $user = User::withoutGlobalScopes()->where('shop_id', $tenantId)->where('id', $userId)->where('deleted', false)->first();
        if (! $user) {
            throw ApiException::notFound('User', $userId);
        }
        $user->deleted = true;
        $user->deleted_at = now();
        $user->save();
        $this->audit->log('DELETE', 'USER', $user->id, 'Admin removed user from tenant', [], 'ADMIN_TENANT_MANAGEMENT');
    }

    private function activateWithPlan(Shop $shop, string $planName, string $expiresAt): void
    {
        $shop->status = 'ACTIVE';
        $shop->plan_name = $planName;
        $shop->subscription_expires_at = Carbon::parse($expiresAt);
        $shop->expiry_alert_sent_at = null;
        $shop->save();
    }

    private function find(int $id): Shop
    {
        $shop = Shop::query()->find($id);
        if (! $shop) {
            throw ApiException::tenantNotFound();
        }

        return $shop;
    }

    private function toListItem(Shop $shop): array
    {
        $userCount = User::withoutGlobalScopes()->where('shop_id', $shop->id)->where('deleted', false)->count();

        return [
            'id' => $shop->id,
            'name' => $shop->name,
            'schemaName' => $shop->schema_name,
            'status' => $shop->status,
            'planName' => $shop->plan_name,
            'subscriptionExpiresAt' => optional($shop->subscription_expires_at)?->toISOString(),
            'userCount' => $userCount,
            'createdAt' => optional($shop->created_at)?->toISOString(),
        ];
    }
}
