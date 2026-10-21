<?php

namespace App\Services;

use App\Models\BillTemplate;
use App\Models\Customer;
use App\Models\NumberSeries;
use App\Models\Permission;
use App\Models\Role;
use App\Models\Shop;
use App\Models\ShopSetting;
use App\Support\ShopContext;

class ShopProvisioningService
{
    public function provision(Shop $shop, ?string $businessType = null): Role
    {
        ShopContext::clear();
        $previous = null;

        $owner = Role::withoutGlobalScopes()->create([
            'shop_id' => $shop->id,
            'name' => 'OWNER',
            'deleted' => false,
            'version' => 0,
        ]);
        Role::withoutGlobalScopes()->create([
            'shop_id' => $shop->id,
            'name' => 'ADMIN',
            'deleted' => false,
            'version' => 0,
        ]);
        Role::withoutGlobalScopes()->create([
            'shop_id' => $shop->id,
            'name' => 'EMPLOYEE',
            'deleted' => false,
            'version' => 0,
        ]);

        $permissions = Permission::query()->where('deleted', false)->get();
        $admin = Role::withoutGlobalScopes()->where('shop_id', $shop->id)->where('name', 'ADMIN')->first();
        $employee = Role::withoutGlobalScopes()->where('shop_id', $shop->id)->where('name', 'EMPLOYEE')->first();

        $employeeNames = ['TENANT_VIEW', 'BILLING_MANAGE', 'CUSTOMER_MANAGE', 'PRODUCT_MANAGE', 'USER_VIEW'];
        foreach ($permissions as $permission) {
            $pivot = [
                'shop_id' => $shop->id,
                'deleted' => false,
                'version' => 0,
                'created_at' => now(),
                'updated_at' => now(),
            ];
            $owner->permissions()->attach($permission->id, $pivot);
            if ($admin) {
                $admin->permissions()->attach($permission->id, $pivot);
            }
            if ($employee && in_array($permission->name, $employeeNames, true)) {
                $employee->permissions()->attach($permission->id, $pivot);
            }
        }

        Customer::withoutGlobalScopes()->create([
            'shop_id' => $shop->id,
            'name' => 'Walk-in Customer',
            'is_walkin' => true,
            'deleted' => false,
            'version' => 0,
        ]);

        $template = BillTemplate::query()->where('code', 'CASH_MEMO_V1')->first();
        ShopSetting::withoutGlobalScopes()->create([
            'shop_id' => $shop->id,
            'bill_template_id' => $template?->id ?? 1,
            'business_type' => $businessType,
            'deleted' => false,
            'version' => 0,
        ]);

        NumberSeries::withoutGlobalScopes()->create([
            'shop_id' => $shop->id,
            'series_type' => 'INVOICE',
            'prefix' => 'INV',
            'financial_year_format' => 'NONE',
            'starting_number' => 1,
            'current_number' => 0,
            'is_active' => true,
            'deleted' => false,
            'version' => 0,
        ]);

        NumberSeries::withoutGlobalScopes()->create([
            'shop_id' => $shop->id,
            'series_type' => 'PURCHASE',
            'prefix' => 'PUR',
            'financial_year_format' => 'NONE',
            'starting_number' => 1,
            'current_number' => 0,
            'is_active' => true,
            'deleted' => false,
            'version' => 0,
        ]);

        return $admin ?? $owner;
    }
}
