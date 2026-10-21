<?php

namespace App\Services;

use App\Models\AuditLog;
use App\Support\ShopContext;
use Illuminate\Http\Request;

class AuditLogger
{
    public function log(
        string $action,
        string $entityType,
        ?int $entityId,
        string $description,
        array $after = [],
        string $module = 'AUTH',
    ): void {
        $user = ShopContext::user();
        $admin = ShopContext::admin();
        $shop = ShopContext::shop();
        $request = request();

        AuditLog::query()->create([
            'deleted' => false,
            'version' => 0,
            'who_user_id' => $user?->id ?? $admin?->id,
            'who_user_email' => $user?->email ?? $admin?->email,
            'who_user_name' => $user?->name ?? $admin?->name,
            'who_role' => $user?->role?->name ?? $admin?->role,
            'what_action' => $action,
            'where_entity_type' => $entityType,
            'where_entity_id' => $entityId,
            'changes_after' => $after ?: null,
            'context_tenant_id' => $shop?->id,
            'context_tenant_name' => $shop?->name,
            'context_ip_address' => $request instanceof Request ? $request->ip() : null,
            'context_user_agent' => $request instanceof Request ? substr((string) $request->userAgent(), 0, 500) : null,
            'description' => $description,
            'module' => $module,
            'severity' => 'INFO',
        ]);
    }
}
