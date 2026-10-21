<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('roles', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('name', 100);
            $table->unique(['shop_id', 'name']);
            $table->unique(['shop_id', 'source_id']);
        });

        Schema::create('users', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('name');
            $table->string('email');
            $table->string('password');
            $table->foreignId('role_id')->nullable()->constrained('roles')->nullOnDelete();
            $table->string('status', 50)->default('ACTIVE');
            $table->unsignedBigInteger('token_version')->default(1);
            $table->boolean('is_first_login')->default(true);
            $table->unique(['shop_id', 'email']);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'status']);
        });

        Schema::create('refresh_tokens', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->string('token', 500)->unique();
            $table->foreignId('user_id')->constrained('users')->restrictOnDelete();
            $table->timestamp('expiry_date', 6);
            $table->boolean('revoked')->default(false);
            $table->index(['shop_id', 'user_id']);
        });

        Schema::create('role_permissions', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->foreignId('role_id')->constrained('roles')->restrictOnDelete();
            $table->foreignId('permission_id')->constrained('permissions')->restrictOnDelete();
            $table->unique(['shop_id', 'role_id', 'permission_id']);
        });

        Schema::create('customers', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('name');
            $table->string('email')->nullable();
            $table->string('phone', 50)->nullable();
            $table->text('address')->nullable();
            $table->string('gstin', 20)->nullable();
            $table->boolean('is_walkin')->default(false);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'is_walkin']);
        });

        Schema::create('products', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('name');
            $table->text('description')->nullable();
            $table->decimal('price', 12, 2)->default(0);
            $table->decimal('tax_percentage', 5, 2)->default(0);
            $table->string('hsn_sac_code', 20)->nullable();
            $table->string('unit', 50)->nullable();
            $table->boolean('is_active')->default(true);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'name']);
        });

        Schema::create('bills', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('invoice_number', 50);
            $table->foreignId('customer_id')->constrained('customers')->restrictOnDelete();
            $table->string('status', 30)->default('DRAFT');
            $table->decimal('subtotal', 12, 2)->default(0);
            $table->decimal('tax_amount', 12, 2)->default(0);
            $table->decimal('discount_amount', 12, 2)->default(0);
            $table->decimal('total_amount', 12, 2)->default(0);
            $table->string('currency', 3)->default('INR');
            $table->text('notes')->nullable();
            $table->timestamp('due_date', 6)->nullable();
            $table->timestamp('paid_at', 6)->nullable();
            $table->string('pdf_url', 500)->nullable();
            $table->unique(['shop_id', 'invoice_number']);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'status']);
            $table->index(['shop_id', 'customer_id']);
        });

        Schema::create('bill_items', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->foreignId('bill_id')->constrained('bills')->restrictOnDelete();
            $table->unsignedBigInteger('product_id')->nullable();
            $table->string('description', 500);
            $table->decimal('quantity', 10, 2)->default(1);
            $table->decimal('unit_price', 12, 2)->default(0);
            $table->decimal('tax_percentage', 5, 2)->default(0);
            $table->decimal('tax_amount', 12, 2)->default(0);
            $table->decimal('total', 12, 2)->default(0);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'bill_id']);
        });

        Schema::create('payments', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->foreignId('bill_id')->constrained('bills')->restrictOnDelete();
            $table->decimal('amount', 12, 2);
            $table->string('currency', 3)->default('INR');
            $table->string('method', 50);
            $table->string('status', 20)->default('CONFIRMED');
            $table->uuid('payment_group_id')->nullable();
            $table->text('notes')->nullable();
            $table->timestamp('paid_at', 6);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'bill_id']);
            $table->index(['shop_id', 'status']);
        });

        Schema::create('shop_settings', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('business_name')->nullable();
            $table->unsignedBigInteger('logo_attachment_id')->nullable();
            $table->string('business_type', 30)->nullable();
            $table->text('address')->nullable();
            $table->string('city', 100)->nullable();
            $table->string('state', 100)->nullable();
            $table->string('pincode', 10)->nullable();
            $table->string('mobile', 20)->nullable();
            $table->string('email')->nullable();
            $table->string('gstin', 15)->nullable();
            $table->string('pan', 10)->nullable();
            $table->string('website')->nullable();
            $table->string('currency', 3)->default('INR');
            $table->string('currency_symbol', 5)->default('₹');
            $table->unsignedTinyInteger('decimal_precision')->default(2);
            $table->string('timezone', 50)->default('Asia/Kolkata');
            $table->string('language', 10)->default('en-IN');
            $table->string('date_format', 20)->default('dd/MM/yyyy');
            $table->string('paper_size', 20)->default('A4');
            $table->unsignedBigInteger('bill_template_id');
            $table->boolean('show_logo')->default(true);
            $table->boolean('show_gst')->default(true);
            $table->boolean('show_qr_code')->default(false);
            $table->boolean('show_customer_address')->default(true);
            $table->boolean('show_amount_in_words')->default(true);
            $table->boolean('show_signature')->default(false);
            $table->text('terms_and_conditions')->nullable();
            $table->text('warranty_text')->nullable();
            $table->string('footer_message', 500)->nullable();
            $table->unsignedTinyInteger('thermal_width')->nullable();
            $table->unsignedTinyInteger('margin')->default(5);
            $table->unsignedTinyInteger('font_size')->default(10);
            $table->string('upi_id', 100)->nullable();
            $table->unsignedBigInteger('signature_attachment_id')->nullable();
            $table->string('watermark_text', 50)->nullable();
            $table->string('bank_name')->nullable();
            $table->string('bank_account_no', 30)->nullable();
            $table->string('bank_ifsc', 11)->nullable();
            $table->string('bank_branch')->nullable();
            $table->unique('shop_id');
        });

        Schema::create('attachments', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('purpose', 30);
            $table->string('file_name');
            $table->string('content_type', 100);
            $table->unsignedBigInteger('size_bytes');
            $table->string('storage_key', 500);
            $table->unique(['shop_id', 'source_id']);
            $table->index(['shop_id', 'purpose']);
        });

        Schema::create('number_series', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->unsignedBigInteger('source_id')->nullable();
            $table->string('series_type', 30);
            $table->string('prefix', 20);
            $table->string('financial_year_format', 20)->default('NONE');
            $table->unsignedBigInteger('starting_number')->default(1);
            $table->unsignedBigInteger('current_number')->default(0);
            $table->boolean('is_active')->default(true);
            $table->index(['shop_id', 'series_type', 'is_active']);
        });

        Schema::create('outbox_events', function (Blueprint $table) {
            $this->audit($table);
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->string('event_type', 100);
            $table->string('aggregate_type', 100)->nullable();
            $table->string('aggregate_id', 100)->nullable();
            $table->text('payload');
            $table->string('event_class');
            $table->string('idempotency_key')->nullable();
            $table->string('status', 20)->default('PENDING');
            $table->unsignedInteger('retry_count')->default(0);
            $table->unsignedInteger('max_retries')->default(5);
            $table->text('last_error')->nullable();
            $table->timestamp('processed_at', 6)->nullable();
            $table->timestamp('locked_until', 6)->nullable();
            $table->index(['shop_id', 'status']);
        });

        Schema::create('password_reset_tokens', function (Blueprint $table) {
            $table->id();
            $table->foreignId('shop_id')->constrained('shops')->restrictOnDelete();
            $table->string('email');
            $table->string('token');
            $table->timestamp('created_at', 6)->nullable();
            $table->index(['shop_id', 'email']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('password_reset_tokens');
        Schema::dropIfExists('outbox_events');
        Schema::dropIfExists('number_series');
        Schema::dropIfExists('attachments');
        Schema::dropIfExists('shop_settings');
        Schema::dropIfExists('payments');
        Schema::dropIfExists('bill_items');
        Schema::dropIfExists('bills');
        Schema::dropIfExists('products');
        Schema::dropIfExists('customers');
        Schema::dropIfExists('role_permissions');
        Schema::dropIfExists('refresh_tokens');
        Schema::dropIfExists('users');
        Schema::dropIfExists('roles');
    }

    private function audit(Blueprint $table): void
    {
        $table->id();
        $table->boolean('deleted')->default(false);
        $table->timestamp('deleted_at', 6)->nullable();
        $table->unsignedBigInteger('version')->default(0);
        $table->timestamp('created_at', 6)->useCurrent();
        $table->timestamp('updated_at', 6)->useCurrent();
        $table->unsignedBigInteger('created_by')->nullable();
        $table->unsignedBigInteger('updated_by')->nullable();
    }
};
