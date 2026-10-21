<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Buy-in register for second-hand phones: who sold the phone to the shop,
 * their ID proof, the device identity (IMEI), price paid, and which bill sold it on.
 * Rows are never hard-deleted — they are the shop's legal backup.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('used_phone_purchases', function (Blueprint $table) {
            $table->id();
            $table->boolean('deleted')->default(false);
            $table->timestamp('deleted_at', 6)->nullable();
            $table->unsignedBigInteger('version')->default(0);
            $table->timestamp('created_at', 6)->useCurrent();
            $table->timestamp('updated_at', 6)->useCurrent();
            $table->unsignedBigInteger('created_by')->nullable();
            $table->unsignedBigInteger('updated_by')->nullable();
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();

            $table->string('voucher_number', 50);
            $table->string('status', 20)->default('IN_STOCK');
            $table->foreignId('product_id')->nullable()->constrained('products')->nullOnDelete();

            $table->string('seller_name');
            $table->string('seller_mobile', 15);
            $table->text('seller_address')->nullable();
            $table->string('seller_id_type', 30);
            $table->string('seller_id_number', 40);
            $table->unsignedBigInteger('id_proof_attachment_id');
            $table->unsignedBigInteger('seller_photo_attachment_id')->nullable();
            $table->unsignedBigInteger('phone_photo_attachment_id')->nullable();
            $table->unsignedBigInteger('seller_signature_attachment_id')->nullable();

            $table->string('brand', 60);
            $table->string('model', 120);
            $table->string('color', 40)->nullable();
            $table->string('storage', 40)->nullable();
            $table->string('imei1', 20);
            $table->string('imei2', 20)->nullable();
            $table->string('serial_number', 60)->nullable();
            $table->string('condition_grade', 20)->nullable();
            $table->unsignedTinyInteger('battery_health')->nullable();
            $table->string('accessories', 255)->nullable();
            $table->boolean('has_original_bill')->default(false);
            $table->boolean('ceir_checked')->default(false);
            $table->boolean('declaration_accepted')->default(false);

            $table->decimal('purchase_price', 12, 2);
            $table->string('payment_method', 30)->default('CASH');
            $table->decimal('selling_price', 12, 2)->nullable();
            $table->text('notes')->nullable();
            $table->timestamp('purchased_at', 6)->useCurrent();

            $table->unsignedBigInteger('sold_bill_id')->nullable();
            $table->unsignedBigInteger('buyer_customer_id')->nullable();
            $table->timestamp('sold_at', 6)->nullable();

            $table->unique(['shop_id', 'voucher_number']);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'imei1']);
            $table->index(['shop_id', 'imei2']);
            $table->index(['shop_id', 'status']);
        });

        if (! Schema::hasColumn('products', 'used_phone_purchase_id')) {
            Schema::table('products', function (Blueprint $table) {
                $table->unsignedBigInteger('used_phone_purchase_id')->nullable();
            });
        }
    }

    public function down(): void
    {
        if (Schema::hasColumn('products', 'used_phone_purchase_id')) {
            Schema::table('products', function (Blueprint $table) {
                $table->dropColumn('used_phone_purchase_id');
            });
        }
        Schema::dropIfExists('used_phone_purchases');
    }
};
