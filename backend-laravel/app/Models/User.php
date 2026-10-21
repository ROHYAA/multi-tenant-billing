<?php

namespace App\Models;

use App\Models\Concerns\Auditable;
use App\Models\Concerns\BelongsToShop;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Foundation\Auth\User as Authenticatable;

class User extends Authenticatable
{
    use Auditable, BelongsToShop;

    protected $guarded = ['id'];

    protected $hidden = ['password'];

    protected function casts(): array
    {
        return [
            'password' => 'hashed',
            'is_first_login' => 'boolean',
            'deleted' => 'boolean',
        ];
    }

    public function shop(): BelongsTo
    {
        return $this->belongsTo(Shop::class);
    }

    public function role(): BelongsTo
    {
        return $this->belongsTo(Role::class);
    }

    public function permissionNames(): array
    {
        if (! $this->role_id) {
            return [];
        }

        return Permission::query()
            ->join('role_permissions', 'permissions.id', '=', 'role_permissions.permission_id')
            ->where('role_permissions.role_id', $this->role_id)
            ->where('role_permissions.shop_id', $this->shop_id)
            ->where('role_permissions.deleted', false)
            ->where('permissions.deleted', false)
            ->pluck('permissions.name')
            ->all();
    }
}
