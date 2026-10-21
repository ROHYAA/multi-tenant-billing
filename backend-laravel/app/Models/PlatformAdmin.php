<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Foundation\Auth\User as Authenticatable;

class PlatformAdmin extends Authenticatable
{
    protected $table = 'platform_admins';

    public $timestamps = false;

    protected $guarded = ['id'];

    protected $hidden = ['password'];

    protected function casts(): array
    {
        return [
            'active' => 'boolean',
            'password' => 'hashed',
            'created_at' => 'datetime',
        ];
    }
}
