<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('shops', function (Blueprint $table) {
            $this->audit($table);
            $table->string('name');
            $table->string('schema_name', 63)->nullable()->unique();
            $table->string('status', 50)->default('PENDING_APPROVAL');
            $table->string('owner_email')->nullable();
            $table->string('slug', 63)->nullable()->unique();
            $table->string('plan_name', 100)->nullable();
            $table->timestamp('subscription_expires_at', 6)->nullable();
            $table->timestamp('expiry_alert_sent_at', 6)->nullable();
            $table->index('status');
        });

        Schema::create('permissions', function (Blueprint $table) {
            $this->audit($table);
            $table->string('name')->unique();
            $table->string('description', 500)->nullable();
        });

        Schema::create('platform_admins', function (Blueprint $table) {
            $table->id();
            $table->string('email', 120)->unique();
            $table->string('password');
            $table->string('name', 120);
            $table->string('role', 50)->default('SUPER_ADMIN');
            $table->boolean('active')->default(true);
            $table->timestamp('created_at', 6)->useCurrent();
        });

        Schema::create('audit_logs', function (Blueprint $table) {
            $this->audit($table);
            $table->unsignedBigInteger('who_user_id')->nullable();
            $table->string('who_user_email')->nullable();
            $table->string('who_user_name')->nullable();
            $table->string('who_role', 100)->nullable();
            $table->string('what_action', 50);
            $table->string('where_entity_type', 50);
            $table->unsignedBigInteger('where_entity_id')->nullable();
            $table->string('where_entity_name', 500)->nullable();
            $table->json('changes_before')->nullable();
            $table->json('changes_after')->nullable();
            $table->json('changes_summary')->nullable();
            $table->unsignedBigInteger('context_tenant_id')->nullable();
            $table->string('context_tenant_name')->nullable();
            $table->string('context_ip_address', 45)->nullable();
            $table->string('context_user_agent', 500)->nullable();
            $table->json('context_metadata')->nullable();
            $table->string('description', 1000)->nullable();
            $table->string('module', 100)->nullable();
            $table->string('severity', 20)->default('INFO');
            $table->index('context_tenant_id');
            $table->index('what_action');
        });

        Schema::create('bill_templates', function (Blueprint $table) {
            $this->audit($table);
            $table->string('code', 50)->unique();
            $table->string('name', 100);
            $table->string('description', 500)->nullable();
            $table->boolean('is_active')->default(true);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('bill_templates');
        Schema::dropIfExists('audit_logs');
        Schema::dropIfExists('platform_admins');
        Schema::dropIfExists('permissions');
        Schema::dropIfExists('shops');
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
