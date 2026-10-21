<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        if (! Schema::hasColumn('products', 'imei')) {
            Schema::table('products', function (Blueprint $table) {
                $table->string('imei', 20)->nullable();
            });
        }
        if (! Schema::hasColumn('bill_items', 'imei')) {
            Schema::table('bill_items', function (Blueprint $table) {
                $table->string('imei', 20)->nullable();
            });
        }
    }

    public function down(): void
    {
        if (Schema::hasColumn('products', 'imei')) {
            Schema::table('products', function (Blueprint $table) {
                $table->dropColumn('imei');
            });
        }
        if (Schema::hasColumn('bill_items', 'imei')) {
            Schema::table('bill_items', function (Blueprint $table) {
                $table->dropColumn('imei');
            });
        }
    }
};
