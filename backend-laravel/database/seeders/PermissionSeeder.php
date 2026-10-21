<?php

namespace Database\Seeders;

use App\Models\Permission;
use Illuminate\Database\Seeder;

class PermissionSeeder extends Seeder
{
    public function run(): void
    {
        $rows = [
            ['TENANT_VIEW', 'View tenant details and settings'],
            ['TENANT_MANAGE', 'Update tenant settings and configurations'],
            ['USER_VIEW', 'View users within the tenant'],
            ['USER_MANAGE', 'Create and update users within the tenant'],
            ['USER_DELETE', 'Delete users from the tenant'],
            ['ROLE_VIEW', 'View roles and their permissions'],
            ['ROLE_MANAGE', 'Create, update and assign roles'],
            ['BILLING_MANAGE', 'Manage invoices and payments'],
            ['CUSTOMER_MANAGE', 'Create, view, update and delete customers'],
            ['PRODUCT_MANAGE', 'Create, view, update and deactivate products'],
        ];

        foreach ($rows as [$name, $description]) {
            Permission::query()->firstOrCreate(
                ['name' => $name],
                ['description' => $description, 'deleted' => false, 'version' => 0]
            );
        }
    }
}
