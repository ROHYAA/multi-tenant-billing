<?php

namespace Tests\Support;

use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

class SqliteSnapshotFixture
{
    public static function connect(): void
    {
        config([
            'database.connections.pgsql_snapshot' => [
                'driver' => 'sqlite',
                'database' => ':memory:',
                'prefix' => '',
                'foreign_key_constraints' => false,
            ],
        ]);
        DB::purge('pgsql_snapshot');
        DB::reconnect('pgsql_snapshot');
    }

    public static function installTwoCollidingShops(string $passwordHash): void
    {
        $schema = Schema::connection('pgsql_snapshot');
        $schema->create('public_shops', function (Blueprint $table) {
            $table->id();
            $table->boolean('deleted')->default(false);
            $table->timestamp('deleted_at')->nullable();
            $table->unsignedBigInteger('version')->default(0);
            $table->timestamp('created_at')->nullable();
            $table->timestamp('updated_at')->nullable();
            $table->unsignedBigInteger('created_by')->nullable();
            $table->unsignedBigInteger('updated_by')->nullable();
            $table->string('name');
            $table->string('schema_name')->nullable();
            $table->string('status')->default('ACTIVE');
            $table->string('owner_email')->nullable();
            $table->string('slug')->nullable();
            $table->string('plan_name')->nullable();
            $table->timestamp('subscription_expires_at')->nullable();
            $table->timestamp('expiry_alert_sent_at')->nullable();
        });
        $schema->create('public_permissions', function (Blueprint $table) {
            $table->id();
            $table->boolean('deleted')->default(false);
            $table->timestamp('deleted_at')->nullable();
            $table->unsignedBigInteger('version')->default(0);
            $table->timestamp('created_at')->nullable();
            $table->timestamp('updated_at')->nullable();
            $table->unsignedBigInteger('created_by')->nullable();
            $table->unsignedBigInteger('updated_by')->nullable();
            $table->string('name');
            $table->string('description')->nullable();
        });
        $schema->create('public_bill_templates', function (Blueprint $table) {
            $table->id();
            $table->boolean('deleted')->default(false);
            $table->timestamp('deleted_at')->nullable();
            $table->unsignedBigInteger('version')->default(0);
            $table->timestamp('created_at')->nullable();
            $table->timestamp('updated_at')->nullable();
            $table->unsignedBigInteger('created_by')->nullable();
            $table->unsignedBigInteger('updated_by')->nullable();
            $table->string('code');
            $table->string('name');
            $table->string('description')->nullable();
            $table->boolean('is_active')->default(true);
        });

        $now = '2024-01-15 06:30:00';
        DB::connection('pgsql_snapshot')->table('public_shops')->insert([
            ['id' => 10, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now, 'name' => 'Alpha', 'schema_name' => 'schema_a', 'status' => 'ACTIVE', 'owner_email' => 'a@example.com', 'slug' => 'alpha'],
            ['id' => 20, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now, 'name' => 'Beta', 'schema_name' => 'schema_b', 'status' => 'ACTIVE', 'owner_email' => 'b@example.com', 'slug' => 'beta'],
        ]);
        DB::connection('pgsql_snapshot')->table('public_permissions')->insert([
            ['id' => 99, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now, 'name' => 'CUSTOMER_MANAGE', 'description' => 'cust'],
        ]);
        DB::connection('pgsql_snapshot')->table('public_bill_templates')->insert([
            ['id' => 5, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now, 'code' => 'CASH_MEMO_V1', 'name' => 'Cash memo', 'is_active' => 1],
        ]);

        foreach (['schema_a' => 10, 'schema_b' => 20] as $schemaName => $shopId) {
            self::tenantTables($schemaName);
            $prefix = 't_'.$schemaName.'__';
            $db = DB::connection('pgsql_snapshot');
            $db->table($prefix.'roles')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now, 'name' => 'OWNER',
            ]);
            $db->table($prefix.'users')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'name' => 'Owner '.$shopId, 'email' => 'owner'.$shopId.'@example.com', 'password' => $passwordHash,
                'role_id' => 1, 'status' => 'ACTIVE', 'token_version' => 1, 'is_first_login' => 0,
            ]);
            $db->table($prefix.'customers')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'name' => 'Walk-in', 'is_walkin' => 1,
            ]);
            $db->table($prefix.'products')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'name' => 'Widget', 'price' => '100.00', 'tax_percentage' => '0.00', 'is_active' => 1,
            ]);
            $db->table($prefix.'bills')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'invoice_number' => 'INV-0001', 'customer_id' => 1, 'status' => 'OPEN',
                'subtotal' => '100.00', 'tax_amount' => '0.00', 'discount_amount' => '0.00',
                'total_amount' => '100.00', 'currency' => 'INR',
            ]);
            $db->table($prefix.'bill_items')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'invoice_id' => 1, 'product_id' => 1, 'description' => 'Widget', 'quantity' => '1.00',
                'unit_price' => '100.00', 'tax_percentage' => '0.00', 'tax_amount' => '0.00', 'total' => '100.00',
            ]);
            $db->table($prefix.'payments')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'invoice_id' => 1, 'amount' => '40.00', 'currency' => 'INR', 'method' => 'CASH',
                'status' => 'CONFIRMED', 'paid_at' => $now,
            ]);
            $db->table($prefix.'number_series')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'series_type' => 'INVOICE', 'prefix' => 'INV-', 'financial_year_format' => 'NONE',
                'starting_number' => 1, 'current_number' => 1, 'is_active' => 1,
            ]);
            $db->table($prefix.'shop_settings')->insert([
                'id' => 1, 'deleted' => 0, 'version' => 0, 'created_at' => $now, 'updated_at' => $now,
                'business_name' => 'Shop '.$shopId, 'bill_template_id' => 5, 'currency' => 'INR',
            ]);
        }
    }

    private static function tenantTables(string $schema): void
    {
        $s = Schema::connection('pgsql_snapshot');
        $audit = function (Blueprint $table): void {
            $table->id();
            $table->boolean('deleted')->default(false);
            $table->timestamp('deleted_at')->nullable();
            $table->unsignedBigInteger('version')->default(0);
            $table->timestamp('created_at')->nullable();
            $table->timestamp('updated_at')->nullable();
            $table->unsignedBigInteger('created_by')->nullable();
            $table->unsignedBigInteger('updated_by')->nullable();
        };
        $p = 't_'.$schema.'__';
        $s->create($p.'roles', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->string('name');
        });
        $s->create($p.'users', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->string('name');
            $table->string('email');
            $table->string('password');
            $table->unsignedBigInteger('role_id')->nullable();
            $table->string('status')->nullable();
            $table->unsignedBigInteger('token_version')->default(1);
            $table->boolean('is_first_login')->default(true);
        });
        $s->create($p.'customers', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->string('name');
            $table->string('email')->nullable();
            $table->string('phone')->nullable();
            $table->text('address')->nullable();
            $table->string('gstin')->nullable();
            $table->boolean('is_walkin')->default(false);
        });
        $s->create($p.'products', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->string('name');
            $table->text('description')->nullable();
            $table->decimal('price', 12, 2)->default(0);
            $table->decimal('tax_percentage', 5, 2)->default(0);
            $table->string('hsn_sac_code')->nullable();
            $table->string('unit')->nullable();
            $table->boolean('is_active')->default(true);
        });
        $s->create($p.'bills', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->string('invoice_number');
            $table->unsignedBigInteger('customer_id');
            $table->string('status');
            $table->decimal('subtotal', 12, 2);
            $table->decimal('tax_amount', 12, 2);
            $table->decimal('discount_amount', 12, 2);
            $table->decimal('total_amount', 12, 2);
            $table->string('currency', 3);
            $table->text('notes')->nullable();
            $table->timestamp('due_date')->nullable();
            $table->timestamp('paid_at')->nullable();
            $table->string('pdf_url')->nullable();
        });
        $s->create($p.'bill_items', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->unsignedBigInteger('invoice_id');
            $table->unsignedBigInteger('product_id')->nullable();
            $table->string('description');
            $table->decimal('quantity', 10, 2);
            $table->decimal('unit_price', 12, 2);
            $table->decimal('tax_percentage', 5, 2);
            $table->decimal('tax_amount', 12, 2);
            $table->decimal('total', 12, 2);
        });
        $s->create($p.'payments', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->unsignedBigInteger('invoice_id');
            $table->decimal('amount', 12, 2);
            $table->string('currency', 3);
            $table->string('method');
            $table->string('status');
            $table->string('payment_group_id')->nullable();
            $table->text('notes')->nullable();
            $table->timestamp('paid_at');
        });
        $s->create($p.'number_series', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->string('series_type');
            $table->string('prefix');
            $table->string('financial_year_format')->nullable();
            $table->unsignedBigInteger('starting_number')->default(1);
            $table->unsignedBigInteger('current_number')->default(0);
            $table->boolean('is_active')->default(true);
        });
        $s->create($p.'shop_settings', function (Blueprint $table) use ($audit) {
            $audit($table);
            $table->string('business_name')->nullable();
            $table->unsignedBigInteger('bill_template_id');
            $table->string('currency', 3)->nullable();
        });
    }
}
